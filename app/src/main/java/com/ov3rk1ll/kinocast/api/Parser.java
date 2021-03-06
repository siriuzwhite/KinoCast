package com.ov3rk1ll.kinocast.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.widget.Toast;

import com.ov3rk1ll.kinocast.CastApp;
import com.ov3rk1ll.kinocast.R;
import com.ov3rk1ll.kinocast.api.mirror.Host;
import com.ov3rk1ll.kinocast.data.ViewModel;
import com.ov3rk1ll.kinocast.ui.DetailActivity;
import com.ov3rk1ll.kinocast.utils.CloudflareDdosInterceptor;
import com.ov3rk1ll.kinocast.utils.CustomDns;
import com.ov3rk1ll.kinocast.utils.InjectedCookieJar;
import com.ov3rk1ll.kinocast.utils.TheMovieDb;
import com.ov3rk1ll.kinocast.utils.UserAgentInterceptor;
import com.ov3rk1ll.kinocast.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class Parser {
    private static final String TAG = Parser.class.getSimpleName();
    private static final int PARSER_ID = -1;
    String URL_BASE;
    private static OkHttpClient client;
    public static InjectedCookieJar injectedCookieJar;

    public static Class<?>[] PARSER_LIST = {
            KinoxParser.class,
            //Movie4kParser.class,
            MyKinoParser.class,
            CineToParser.class,
            HDFilmeParser.class,
            NetzkinoParser.class,
            TVStreamsParser.class
    };

    private static Parser instance;
    public static void setInstance(Parser parser) {
        instance = parser;
    }

        public static Parser getInstance() {
        if(instance == null) {
            Context context = CastApp.getContext();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            String parser = preferences.getString("parser", Integer.toString(KinoxParser.PARSER_ID));
            Parser.selectParser(context, Integer.parseInt(parser));
            if(Parser.getInstance() == null){
                Parser.selectParser(context, KinoxParser.PARSER_ID);
            }
            Log.i("selectParser", "ID is " + Parser.getInstance().getParserId());
        }
        return instance;
    }

    public static void selectParser(Context context, int id) {
        instance = selectByParserId(context, id);
        instance.initHttpClient(context);
    }

    public static void selectParser(Context context, int id, String url) {
        instance = selectByParserId(id, url);
        instance.initHttpClient(context);
    }

    public static Parser selectByParserId(Context context, int id) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String url = preferences.getString("url", "");
        //prevent app from crashing with empty url
        if (!url.equalsIgnoreCase("") && !Patterns.WEB_URL.matcher(url).matches()) {
            url = "";
            SharedPreferences.Editor prefEditor = preferences.edit();
            prefEditor.putString("url", url);
            prefEditor.commit();
            Toast.makeText(context, "Resetting invalid URL to default", Toast.LENGTH_SHORT);
        }
        return selectByParserId(id, url);
    }

    public static Parser ParserById(int id) {
        Parser def = null;
        for (Class<?> h : PARSER_LIST) {
            try {
                Parser parser = (Parser) h.getConstructor().newInstance();
                if(def == null) def = parser;
                if (parser.getParserId() == id) {
                    return parser;
                }
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
        if(def == null) def = new KinoxParser();
        return def;
    }

    private static Parser selectByParserId(int id, String url) {
        Parser p = ParserById(id);
        if (p != null && !"".equals(url)) p.setUrl(url);
        return p;
    }


    private void initHttpClient(Context context) {
        injectedCookieJar = InjectedCookieJar.Build(context);
        List<Cookie> colist = injectedCookieJar.loadForRequest(HttpUrl.parse(URL_BASE));
        injectedCookieJar.clear();
        for (Cookie co: colist) {
            injectedCookieJar.addCookie(co);
        }

        client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor(new UserAgentInterceptor(Utils.USER_AGENT))
                .addInterceptor(new CloudflareDdosInterceptor(context))
                .cookieJar(injectedCookieJar)
                .dns(new CustomDns())
                .build();
    }

    public static Document getDocument(String url) throws IOException {
        return getDocument(url, null);
    }

    public static Document getDocument(String url, Map<String, String> cookies) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        if (cookies != null) {
            for (String key : cookies.keySet()) {
                injectedCookieJar.addCookie(new Cookie.Builder()
                        .domain(request.url().host())
                        .name(key)
                        .value(cookies.get(key))
                        .build()
                );
            }
        }
        Response response = client.newCall(request).execute();
        String body = response.body().string();
        if (response.code() != 200) {
            Log.d(TAG, body);
            throw new IOException("Unexpected status code " + response.code());
        }
        if (TextUtils.isEmpty(body)) {
            throw new IOException("Body for " + url + " is empty");
        }
        return Jsoup.parse(body);
    }

    //seems to need interceptor too
    public String getBody(String url) {

        /*
        Request request = new Request.Builder().url(url).build();
        try {
            Response response = client.newCall(request).execute();
            for(String key : response.headers().names()){
                Log.i(TAG, key + "=" + response.header(key));
            }
            return response.body().string();
        } catch (IOException e){
            e.printStackTrace();
        }
        return null;
        */

        OkHttpClient noFollowClient = client.newBuilder().followRedirects(false).build();
        Request request = new Request.Builder().url(url).build();
        Log.i(TAG, "read text from " + url + ", cookies=" + noFollowClient.cookieJar().toString());
        try {
            Response response = noFollowClient.newCall(request).execute();
            Log.i(TAG, "Got " + response.code() + " for " + url + ", cookies=" + noFollowClient.cookieJar().toString());
            for (String key : response.headers().names()) {
                Log.i(TAG, key + "=" + response.header(key));
            }
            String body = response.body().string();
            Log.d(TAG, body);

            return body;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;

    }


    JSONObject getJson(String url) {
        Request request = new Request.Builder().url(url).build();

        Log.i("Utils", "read json from " + url);
        try {
            Response response = client.newCall(request).execute();
            return new JSONObject(response.body().string());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Parser() {
        this.URL_BASE = getDefaultUrl();
    }

    public Parser(String url) {
        this.setUrl(url);
    }

    public abstract String getDefaultUrl();

    public abstract String getParserName();

    public abstract int getParserId();

    public abstract List<ViewModel> parseList(String url) throws IOException;

    public abstract ViewModel loadDetail(ViewModel item);

    public abstract ViewModel loadDetail(String url);

    public abstract List<Host> getHosterList(ViewModel item, int season, String episode);

    public abstract String getMirrorLink(DetailActivity.QueryPlayTask queryTask, ViewModel item, Host host);

    public abstract String getMirrorLink(DetailActivity.QueryPlayTask queryTask, ViewModel item, Host host, int season, String episode);

    public abstract String[] getSearchSuggestions(String query);

    public abstract String getPageLink(ViewModel item);

    public abstract String getSearchPage(String query);

    public abstract String getCineMovies();

    public abstract String getPopularMovies();

    public abstract String getLatestMovies();

    public abstract String getPopularSeries();

    public abstract String getLatestSeries();

    public String getUrl() {
        return URL_BASE;
    }

    public void setUrl(String url) {
        if(Utils.isStringEmpty(url)) url = getDefaultUrl();
        URL_BASE = url;
    }

    @Override
    public String toString() {
        return getParserName();
    }

    public OkHttpClient getClient() {
        return client;
    }


    public void updateFromCache(TheMovieDb moviedb, ViewModel model){
        JSONObject data = moviedb.get(Parser.getInstance().getImdbLink(model), false);
        if(data == null) return;

        if(model.getRating()==0) {
            try {
                model.setRating((float)data.getDouble("vote_average"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        if("".equalsIgnoreCase(model.getTitle())) {
            try {
                model.setTitle(data.getString("title"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        if("".equalsIgnoreCase(model.getSummary())) {
            try {
                model.setSummary(data.getString("overview"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public String getImdbLink(ViewModel item){
        String ln = languageDrawMap.get(item.getLanguageResId());
        return getPageLink(item) + "#language="+ln;
    }

    private static final SparseArray<String> languageDrawMap = new SparseArray<>();
    static {
        languageDrawMap.put(R.drawable.lang_de, "de");
        languageDrawMap.put(R.drawable.lang_en, "en");
        languageDrawMap.put(R.drawable.lang_zh, "zh");
        languageDrawMap.put(R.drawable.lang_es, "es");
        languageDrawMap.put(R.drawable.lang_fr, "fr");
        languageDrawMap.put(R.drawable.lang_tr, "tr");
        languageDrawMap.put(R.drawable.lang_jp, "jp");
        languageDrawMap.put(R.drawable.lang_ar, "ar");
        languageDrawMap.put(R.drawable.lang_it, "it");
        languageDrawMap.put(R.drawable.lang_hr, "hr");
        languageDrawMap.put(R.drawable.lang_sr, "sr");
        languageDrawMap.put(R.drawable.lang_bs, "bs");
        languageDrawMap.put(R.drawable.lang_de_en, "de");
        languageDrawMap.put(R.drawable.lang_nl, "nl");
        languageDrawMap.put(R.drawable.lang_ko, "ko");
        languageDrawMap.put(R.drawable.lang_el, "el");
        languageDrawMap.put(R.drawable.lang_ru, "ru");
        languageDrawMap.put(R.drawable.lang_hi, "hi");
    }

    public String PreSaveParserUrl(String newUrl){
        return newUrl;
    }


}
