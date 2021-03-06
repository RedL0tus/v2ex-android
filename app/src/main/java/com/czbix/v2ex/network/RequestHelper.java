package com.czbix.v2ex.network;

import android.os.Build;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.czbix.v2ex.AppCtx;
import com.czbix.v2ex.BuildConfig;
import com.czbix.v2ex.common.DeviceStatus;
import com.czbix.v2ex.common.UserState;
import com.czbix.v2ex.common.exception.ConnectionException;
import com.czbix.v2ex.common.exception.ExIllegalStateException;
import com.czbix.v2ex.common.exception.RemoteException;
import com.czbix.v2ex.common.exception.RequestException;
import com.czbix.v2ex.common.exception.UnauthorizedException;
import com.czbix.v2ex.model.Comment;
import com.czbix.v2ex.model.Favable;
import com.czbix.v2ex.model.Ignorable;
import com.czbix.v2ex.model.LoginResult;
import com.czbix.v2ex.model.Node;
import com.czbix.v2ex.model.Notification;
import com.czbix.v2ex.model.Page;
import com.czbix.v2ex.model.Tab;
import com.czbix.v2ex.model.Thankable;
import com.czbix.v2ex.model.Topic;
import com.czbix.v2ex.model.TopicWithComments;
import com.czbix.v2ex.model.json.TopicBean;
import com.czbix.v2ex.parser.MyselfParser;
import com.czbix.v2ex.parser.NotificationParser;
import com.czbix.v2ex.parser.Parser;
import com.czbix.v2ex.parser.Parser.PageType;
import com.czbix.v2ex.parser.TopicListParser;
import com.czbix.v2ex.parser.TopicParser;
import com.czbix.v2ex.util.GsonUtilsKt;
import com.czbix.v2ex.util.IoUtils;
import com.czbix.v2ex.util.LogUtils;
import com.czbix.v2ex.util.TrackerUtils;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.CookiePersistor;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import com.google.gson.reflect.TypeToken;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import kotlin.Triple;
import kotlin.jvm.functions.Function1;
import okhttp3.Cache;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import rx.Observable;

public class RequestHelper {
    public static final String BASE_URL = "https://www.v2ex.com";
    private static final String USER_AGENT = "V2EX+/" + BuildConfig.VERSION_NAME;
    private static String USER_AGENT_ANDROID = String.format("%s (Android %s)", USER_AGENT, Build.VERSION.RELEASE);

    private static final String TAG = RequestHelper.class.getSimpleName();
    private static final String API_GET_ALL_NODES = BASE_URL + "/api/nodes/all.json";
    private static final String API_GET_TOPIC = BASE_URL + "/api/topics/show.json";
    private static final String URL_SIGN_IN = BASE_URL + "/signin";
    private static final String URL_MISSION_DAILY = BASE_URL + "/mission/daily";
    private static final String URL_ONCE_TOKEN = URL_SIGN_IN;
    private static final String URL_NOTIFICATIONS = BASE_URL + "/notifications";
    private static final String URL_FAVORITE_NODES = BASE_URL + "/my/nodes";
    private static final String URL_UNREAD_NOTIFICATIONS = BASE_URL + "/mission";
    private static final String URL_NEW_TOPIC = BASE_URL + "/new/%s";
    private static final String URL_GOOGLE_SIGN_IN = BASE_URL + "/auth/google?once=%s";

    private static final int SERVER_ERROR_CODE = 500;

    private static final OkHttpClient CLIENT;

    private static PersistentCookieJar cookieJar;

    static {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.cache(buildCache());
        builder.connectTimeout(10, TimeUnit.SECONDS);
        builder.writeTimeout(10, TimeUnit.SECONDS);
        builder.readTimeout(30, TimeUnit.SECONDS);
        builder.followRedirects(false);

        cookieJar = new PersistentCookieJar(new SetCookieCache(),
                new SharedPrefsCookiePersistor(AppCtx.getInstance()));
        builder.cookieJar(cookieJar);

        CLIENT = builder.build();
    }

    private static Cache buildCache() {
        final File cacheDir = IoUtils.getWebCachePath();
        final int cacheSize = 64 * 1024 * 1024;

        return new Cache(cacheDir, cacheSize);
    }

    static Request.Builder newRequest() {
        Request.Builder builder = new Request.Builder();
        builder.header(HttpHeaders.USER_AGENT, USER_AGENT);

        return builder;
    }

    static OkHttpClient getClient() {
        return CLIENT;
    }

    public static void clearCookies() {
        cookieJar.clear();
    }

    public static List<Topic> getTopics(Page page) throws ConnectionException, RemoteException {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "request latest topic for page: " + page.getTitle());
        }

        final Request request = newRequest()
                .url(page.getUrl())
                .build();

        final Response response = sendRequest(request);
        if (response.isRedirect()) {
            throw new ExIllegalStateException("topics page should not redirect");
        }

        final Document doc;
        final List<Topic> topics;
        try {
            doc = Parser.toDoc(response.body().string());
            processUserState(doc, page instanceof Tab ? PageType.Tab : PageType.Node);
            topics = TopicListParser.parseDoc(doc, page);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "received topics, count: " + topics.size());
        }

        return topics;
    }

    public static TopicWithComments getTopicWithComments(Topic topic, int page) throws ConnectionException, RemoteException {
        Preconditions.checkArgument(page > 0, "page must greater than zero");

        LogUtils.v(TAG, "request topic with comments, id: %d, title: %s", topic.getId(), topic.getTitle());

        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(topic.getUrl() + "?p=" + page)
                .build();
        final Response response = sendRequest(request);
        if (response.isRedirect()) {
            throw new ExIllegalStateException("topic page shouldn't redirect");
        }

        final Document doc;
        final TopicWithComments result;

        try {
            doc = Parser.toDoc(response.body().string());
            processUserState(doc, PageType.Topic);

            final Stopwatch stopwatch = Stopwatch.createStarted();
            result = TopicParser.parseDoc(doc, topic);
            TrackerUtils.onParseTopic(stopwatch.elapsed(TimeUnit.MILLISECONDS),
                    Integer.toString(result.mComments.size()));
        } catch (IOException e) {
            throw new ConnectionException(e);
        }

        return result;
    }

    public static Observable<Topic> getTopicByApi(Topic topic) {
        LogUtils.v(TAG, "request topic by api, id: %d, title: %s", topic.getId(), topic.getTitle());

        return Observable.create(subscriber -> {
            final Request request = newRequest()
                    .url(API_GET_TOPIC + "?id=" + topic.getId())
                    .build();
            try {
                final Response response = sendRequest(request);
                final List<TopicBean> list = GsonUtilsKt.getGSON().fromJson(response.body().charStream(),
                        new TypeToken<List<TopicBean>>() {
                        }.getType());

                final Topic result = list.get(0).toModel();
                subscriber.onNext(result);
                subscriber.onCompleted();
            } catch (Exception e) {
                if (e instanceof IOException) {
                    e = new ConnectionException(e);
                }
                subscriber.onError(e);
            }
        });
    }

    private static void processUserState(Document doc, PageType pageType) {
        if (!UserState.getInstance().isLoggedIn()) {
            return;
        }

        final MyselfParser.MySelfInfo info = MyselfParser.parseDoc(doc, pageType);
        UserState.getInstance().handleInfo(info, pageType);
    }

    @Nullable
    public static List<Node> getAllNodes(Etag etag) throws ConnectionException, RemoteException {
        Preconditions.checkNotNull(etag);

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "request all nodes");
        }

        final Request request = newRequest().url(API_GET_ALL_NODES).build();
        final Response response = sendRequest(request);

        final String newEtag = response.header(HttpHeaders.ETAG);
        if (!etag.setNewEtag(newEtag)) {
            return null;
        }

        try {
            final String json = response.body().string();

            return GsonUtilsKt.getGSON().fromJson(json, new TypeToken<List<Node>>() {
            }.getType());
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static List<Node> getFavNodes() throws ConnectionException, RemoteException {
        Preconditions.checkState(UserState.getInstance().isLoggedIn(), "guest can't check notifications");
        LogUtils.v(TAG, "get favorite nodes");

        final Request request = newRequest().url(URL_FAVORITE_NODES).build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();

            final Document doc = Parser.toDoc(html);
            return MyselfParser.parseFavNodes(doc);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static int getUnreadNum() throws ConnectionException, RemoteException {
        Preconditions.checkState(UserState.getInstance().isLoggedIn(), "guest can't check notifications");
        LogUtils.v(TAG, "get unread num");

        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(URL_UNREAD_NOTIFICATIONS).build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();

            final Document doc = Parser.toDoc(html);
            return NotificationParser.parseUnreadCount(doc);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static List<Notification> getNotifications() throws ConnectionException, RemoteException {
        Preconditions.checkState(UserState.getInstance().isLoggedIn(), "guest can't check notifications");
        LogUtils.v(TAG, "get notifications");

        final Request request = newRequest().url(URL_NOTIFICATIONS)
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();

            final Document doc = Parser.toDoc(html);
            return NotificationParser.parseDoc(doc);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static boolean reply(Topic topic, String content, String once) throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "reply to topic: %s", topic.getTitle());

        if (Strings.isNullOrEmpty(once)) {
            once = getOnceToken();
        }
        final RequestBody requestBody = new FormBody.Builder().add("once", once)
                .add("content", content.replace("\n", "\r\n"))
                .build();

        final Request request = newRequest().url(topic.getUrl())
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .post(requestBody).build();
        final Response response = sendRequest(request, false);

        // v2ex will redirect if reply success
        return response.code() == HttpStatus.SC_MOVED_TEMPORARILY;
    }

    public static void ignore(Ignorable obj, String onceToken) throws ConnectionException, RemoteException {
        final Request.Builder builder = newRequest().url(obj.getIgnoreUrl() + "?once=" + onceToken);
        final boolean isComment = obj instanceof Comment;
        if (isComment) {
            builder.post(RequestBody.create(null, new byte[0]));
        }
        final Request request = builder.build();

        sendRequest(request, isComment);
    }

    public static void favor(Favable obj, boolean isFavor, String csrfToken) throws ConnectionException, RemoteException {
        final String url = isFavor ? obj.getFavUrl() : obj.getUnFavUrl();
        final Request request = newRequest().url(url + "?t=" + csrfToken)
                .build();

        final Response response = sendRequest(request, false);
        if (!response.isRedirect()) {
            throw new RequestException(String.format("favor %s failed, is fav: %b", obj,
                    isFavor), response);
        }
    }

    public static void thank(Thankable obj, String csrfToken) throws ConnectionException, RemoteException {
        final Request request = newRequest().url(obj.getThankUrl() + "?t=" + csrfToken)
                .post(RequestBody.create(null, new byte[0])).build();

        sendRequest(request);
    }

    public static void dailyMission() throws ConnectionException, RemoteException {
        final String onceCode = getOnceToken();
        final Request request = newRequest().url(String.format("%s/redeem?once=%s",
                URL_MISSION_DAILY, onceCode))
                .header(HttpHeaders.REFERER, URL_MISSION_DAILY)
                .build();

        final Response response = sendRequest(request, false);

        if (response.code() != HttpStatus.SC_MOVED_TEMPORARILY) {
            throw new RequestException(response);
        }
    }

    public static int newTopic(String nodeName, String title, String content) throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "new topic in node: %s, title: %s", nodeName, title);

        final String once = getOnceToken();
        final RequestBody requestBody = new FormBody.Builder().add("once", once)
                .add("title", title)
                .add("content", content.replace("\n", "\r\n"))
                .build();

        final Request request = newRequest().url(String.format(URL_NEW_TOPIC, nodeName))
                .post(requestBody).build();
        final Response response = sendRequest(request);

        // v2ex will redirect if reply success
        if (response.code() == HttpStatus.SC_MOVED_TEMPORARILY) {
            final String location = response.header(HttpHeaders.LOCATION);
            return Topic.getIdFromUrl(location);
        }

        final RequestException exception = new RequestException("post new topic failed", response);
        try {
            exception.setErrorHtml(TopicParser.parseProblemInfo(response.body().string()));
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
        throw exception;
    }

    public static boolean hasDailyAward() throws ConnectionException, RemoteException {
        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(URL_MISSION_DAILY).build();

        final Response response = sendRequest(request);

        final String html;
        try {
            html = response.body().string();
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
        return MyselfParser.hasAward(html);
    }

    public static LoginResult login(String account, String password) throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "login user: " + account);

        final Triple<String, String, String> signInForm = getSignInForm();
        final String nextUrl = "/mission";
        final RequestBody requestBody = new FormBody.Builder().add("once", signInForm.component3())
                .add(signInForm.component1(), account)
                .add(signInForm.component2(), password)
                .add("next", nextUrl)
                .build();
        Request request = newRequest().url(URL_SIGN_IN)
                .header(HttpHeaders.REFERER, URL_SIGN_IN)
                .post(requestBody).build();
        Response response = sendRequest(request, false);

        return innerLogin(response, new Function1<String, Boolean>() {
            @Override
            public Boolean invoke(String s) {
                return s.equals(nextUrl);
            }
        });
    }

    @NotNull
    private static LoginResult innerLogin(Response response, Function1<String, Boolean> isValidLocation) throws ConnectionException, RemoteException {
        // v2ex will redirect if login success
        if (response.code() != HttpStatus.SC_MOVED_TEMPORARILY) {
            throw new RequestException("code should not be " + response.code(), response);
        }

        final String location = response.header(HttpHeaders.LOCATION);
        if (!isValidLocation.invoke(location)) {
            throw new RequestException("location should not be " + location, response);
        }

        final Request request = newRequest().url(BASE_URL + location).build();
        response = sendRequest(request);

        try {
            final String html = response.body().string();
            final Document document = Parser.toDoc(html);
            return MyselfParser.parseLoginResult(document);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static LoginResult loginViaGoogle(String urlWithCode) throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "login via google: %s", urlWithCode);

        final Request request = newRequest()
                .url(urlWithCode).build();
        final Response response = sendRequest(request, false);

        return innerLogin(response, new Function1<String, Boolean>() {
            @Override
            public Boolean invoke(String s) {
                return s.equals("/") || s.equals("/settings/username");
            }
        });
    }

    public static String getOnceToken() throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "get once token");

        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(URL_ONCE_TOKEN).build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();
            return Parser.parseOnceCode(html);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static Triple<String, String, String> getSignInForm() throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "get sign in form");

        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(URL_ONCE_TOKEN).build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();
            return Parser.parseSignInForm(html);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    public static String getGoogleSignInUrl() throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "get google sign in url");

        final String onceCode = getOnceToken();
        final String url = String.format(URL_GOOGLE_SIGN_IN, onceCode);

        final Request request = newRequest()
                .header(HttpHeaders.USER_AGENT, USER_AGENT_ANDROID)
                .url(url).build();
        final Response response = sendRequest(request, false);

        if (response.code() == HttpStatus.SC_MOVED_TEMPORARILY) {
            final String location = response.header(HttpHeaders.LOCATION);
            // google login url
            if (location.startsWith("https")) {
                return location;
            }
        }
        throw new RequestException(response);
    }

    public static String getNotificationsToken() throws ConnectionException, RemoteException {
        LogUtils.v(TAG, "get notifications token");

        final Request request = newRequest()
                .url(URL_NOTIFICATIONS).build();
        final Response response = sendRequest(request);

        try {
            final String html = response.body().string();
            return NotificationParser.parseToken(html);
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
    }

    static Response sendRequest(Request request) throws ConnectionException, RemoteException {
        return sendRequest(request, true);
    }

    static Response sendRequest(Request request, boolean checkResponse) throws ConnectionException, RemoteException {
        if (!DeviceStatus.getInstance().isNetworkConnected()) {
            throw new ConnectionException("network not connected");
        }

        if (BuildConfig.DEBUG && new Random().nextInt(100) > 95) {
            throw new ConnectionException("debug network test");
        }

        final Response response;
        try {
            response = CLIENT.newCall(request).execute();
        } catch (IOException e) {
            throw new ConnectionException(e);
        }
        if (checkResponse) {
            checkResponse(response);
        }

        return response;
    }

    private static void checkResponse(Response response) throws RemoteException, RequestException, ConnectionException {
        if (response.isSuccessful()) {
            return;
        }

        final int code = response.code();
        if (code >= HttpStatus.SC_MULTIPLE_CHOICES && code < HttpStatus.SC_BAD_REQUEST) {
            if (response.isRedirect() && response.header(HttpHeaders.LOCATION).startsWith("/signin")) {
                throw new UnauthorizedException(response);
            }
            return;
        }

        if (code >= SERVER_ERROR_CODE) {
            throw new RemoteException(response);
        }


        Crashlytics.log("request url: " + response.request().url());
        if (code == 403 || code == 404) {
            try {
                final ResponseBody body = response.body();
                //noinspection StatementWithEmptyBody
                if (body.contentLength() == 0) {
                    // it's blocked for new user
                } else {
                    // topic deleted, record logs for debug
                    final String bodyStr = body.string();
                    Crashlytics.log(String.format("response code %d, %s", code,
                            bodyStr.substring(0, Math.min(4096, bodyStr.length()))));
                }

                final RequestException ex = new RequestException(response);
                ex.setShouldLogged(false);
                throw ex;
            } catch (IOException e) {
                throw new ConnectionException(e);
            }
        }

        throw new RequestException(response);
    }
}
