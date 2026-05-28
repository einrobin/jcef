// Copyright 2024 The JCEF Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package tests.drm;

import com.jetbrains.cef.JCefAppConfig;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.DrmSupport;
import org.cef.SystemBootstrap;
import org.cef.browser.*;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.misc.CefLog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * DRM/Widevine test application for JCEF.
 * Verifies that Widevine CDM is properly loaded and DRM content can be played.
 *
 * Usage:
 *   java -cp ... tests.drm.DrmTest [--headless] [--probe-only]
 */
public class DrmTest extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String DRM_TEST_URL = "https://bitmovin.com/demos/drm/";
    private static final int TIMEOUT_SECONDS = 60;

    private final CefApp cefApp_;
    private final CefClient client_;
    private final CefBrowser browser_;
    private volatile String probeResult_ = null;
    private volatile boolean pageLoaded_ = false;

    private static boolean headless = false;
    private static boolean probeOnly = false;

    public DrmTest(CefApp cefApp, String url) {
        cefApp_ = cefApp;
        client_ = cefApp_.createClient();

        // Use windowed mode for DRM (OSR may not support protected content)
        browser_ = client_.createBrowser(url, false, false);

        client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser browser, String title) {
                if (title != null && title.startsWith("DRM_PROBE:")) {
                    String encoded = title.substring("DRM_PROBE:".length());
                    try {
                        byte[] decoded = Base64.getDecoder().decode(encoded);
                        probeResult_ = new String(decoded, StandardCharsets.UTF_8);
                        System.out.println("[JCEF][DRM_TEST] Probe result: " + probeResult_);
                    } catch (Exception e) {
                        System.err.println("[JCEF][DRM_TEST] Failed to decode probe: " + e.getMessage());
                        probeResult_ = "{\"error\":\"decode_failed\"}";
                    }
                }
            }
        });

        client_.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain()) {
                    pageLoaded_ = true;
                    System.out.println("[JCEF][DRM_TEST] Page loaded: " + browser.getURL()
                            + " (status=" + httpStatusCode + ")");
                    injectDrmProbe(browser, frame);
                }
            }
        });

        if (!headless) {
            Component browserUI = browser_.getUIComponent();
            getContentPane().add(browserUI, BorderLayout.CENTER);
            setSize(1024, 768);
            setTitle("JCEF DRM Test");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setVisible(true);
        }
    }

    private void injectDrmProbe(CefBrowser browser, CefFrame frame) {
        // Probe checks both proprietary (H.264/AAC) and open (VP9/VP8/Opus) codec configurations.
        // The minimal CEF build only supports open codecs; the full build supports both.
        String script =
                "(function(){"
                        + "const report=(obj)=>{"
                        + "try{document.title='DRM_PROBE:'+btoa(unescape(encodeURIComponent(JSON.stringify(obj))));}"
                        + "catch(e){document.title='DRM_PROBE:'+btoa(JSON.stringify({error:'encode_failed',message:String(e)}));}"
                        + "};"
                        + "const v=document.createElement('video');"
                        + "const result={"
                        + "url:location.href,"
                        + "ua:navigator.userAgent,"
                        + "emeApi:(typeof navigator.requestMediaKeySystemAccess==='function'),"
                        + "canPlayAvc:v.canPlayType('video/mp4; codecs=\"avc1.42E01E\"'),"
                        + "canPlayAac:v.canPlayType('audio/mp4; codecs=\"mp4a.40.2\"'),"
                        + "canPlayVp9:v.canPlayType('video/webm; codecs=\"vp9\"'),"
                        + "canPlayVp8:v.canPlayType('video/webm; codecs=\"vp8\"')"
                        + "};"
                        + "if(!result.emeApi){result.widevine='missing_api';report(result);return;}"
                        + "const configs=["
                        // Config 1: Open codecs (VP9/VP8 + Opus/Vorbis) - works with minimal build
                        + "{initDataTypes:['cenc','webm'],"
                        + "videoCapabilities:["
                        + "{contentType:'video/webm; codecs=\"vp9\"'},"
                        + "{contentType:'video/webm; codecs=\"vp8\"'}"
                        + "],"
                        + "audioCapabilities:["
                        + "{contentType:'audio/webm; codecs=\"opus\"'},"
                        + "{contentType:'audio/webm; codecs=\"vorbis\"'}"
                        + "]},"
                        // Config 2: Proprietary codecs (H.264/AAC) - requires full build
                        + "{initDataTypes:['cenc'],"
                        + "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.42E01E\"'}],"
                        + "audioCapabilities:[{contentType:'audio/mp4; codecs=\"mp4a.40.2\"'}]"
                        + "}"
                        + "];"
                        + "navigator.requestMediaKeySystemAccess('com.widevine.alpha',configs)"
                        + ".then((access)=>{"
                        + "result.widevine='ok';"
                        + "result.configuration=access.getConfiguration();"
                        + "report(result);"
                        + "})"
                        + ".catch((err)=>{"
                        + "result.widevine='error';"
                        + "result.errorName=(err&&err.name)?err.name:'Error';"
                        + "result.errorMessage=(err&&err.message)?err.message:String(err);"
                        + "report(result);"
                        + "});"
                        + "})();";
        frame.executeJavaScript(script, browser.getURL(), 0);
        System.out.println("[JCEF][DRM_PROBE] Probe injected on " + browser.getURL());
    }

    public String waitForProbeResult() {
        long start = System.currentTimeMillis();
        while (probeResult_ == null && (System.currentTimeMillis() - start) < TIMEOUT_SECONDS * 1000L) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        return probeResult_;
    }

    public boolean isWidevineWorking() {
        String result = waitForProbeResult();
        if (result == null) {
            System.err.println("[JCEF][DRM_TEST] TIMEOUT: No probe result received within " + TIMEOUT_SECONDS + "s");
            return false;
        }
        return result.contains("\"widevine\":\"ok\"");
    }

    private static CefApp createCefApp(String[] args) {
        // Determine native bundle path: use system property or auto-detect from java.library.path
        String nativePath = System.getProperty("jcef.native.path");
        if (nativePath == null) {
            String libPath = System.getProperty("java.library.path", "");
            if (!libPath.isEmpty()) {
                nativePath = libPath.split(File.pathSeparator)[0];
            }
        }

        JCefAppConfig config;
        if (nativePath != null && !nativePath.isEmpty()) {
            config = JCefAppConfig.getInstance(nativePath);
            System.out.println("[JCEF][DRM_TEST] Using native bundle path: " + nativePath);
        } else {
            config = JCefAppConfig.getInstance();
        }

        // Set up the library loader BEFORE startup
        if (config.getLoader() != null) {
            SystemBootstrap.setLoader(config.getLoader());
        }

        List<String> appArgs = new ArrayList<>(Arrays.asList(args));
        appArgs.addAll(config.getAppArgsAsList());

        CefAppHandlerAdapter appHandler = new CefAppHandlerAdapter(appArgs.toArray(new String[0])) {
            @Override
            public void stateHasChanged(CefAppState state) {
                if (state == CefAppState.TERMINATED)
                    System.exit(0);
            }
        };
        CefApp.addAppHandler(appHandler);

        CefSettings settings = config.getCefSettings();

        // DRM REQUIREMENT: cache_path must be set for component updater to download Widevine CDM
        if (settings.cache_path == null || settings.cache_path.isEmpty()) {
            String cacheDir = System.getProperty("jcef.drm.cache_path",
                    System.getProperty("user.home") + File.separator + ".jcef" + File.separator + "cache");
            settings.cache_path = cacheDir;
            System.out.println("[JCEF][DRM_TEST] Setting cache_path for DRM: " + cacheDir);
        }

        // No sandbox for CDM loading
        settings.no_sandbox = true;

        return CefApp.getInstance(null, settings, null);
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if ("--headless".equals(arg)) headless = true;
            if ("--probe-only".equals(arg)) probeOnly = true;
        }

        CefApp.startup(args);
        CefLog.initVerbose();

        System.out.println("[JCEF][DRM_TEST] Starting DRM/Widevine test...");
        System.out.println("[JCEF][DRM_TEST] Platform: " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));

        CefApp cefApp = createCefApp(args);
        // EME requires a secure context (HTTPS); use google.com for probe-only mode
        String url = probeOnly ? "https://www.google.com" : DRM_TEST_URL;

        DrmTest test = new DrmTest(cefApp, url);

        if (headless || probeOnly) {
            boolean widevineOk = test.isWidevineWorking();
            System.out.println("[JCEF][DRM_TEST] ============================");
            System.out.println("[JCEF][DRM_TEST] Widevine CDM Status: " + (widevineOk ? "WORKING" : "NOT WORKING"));
            System.out.println("[JCEF][DRM_TEST] ============================");

            if (!widevineOk) {
                System.out.println("[JCEF][DRM_TEST] Note: Widevine CDM may need time to download on first run.");
                System.out.println("[JCEF][DRM_TEST] Ensure cache_path is set and component updater is not disabled.");
            }

            cefApp.dispose();
            System.exit(widevineOk ? 0 : 1);
        } else {
            test.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    cefApp.dispose();
                }
            });
        }
    }
}
