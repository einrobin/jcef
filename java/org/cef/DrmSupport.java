// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.cef;

import org.cef.callback.CefCommandLine;

import java.io.*;
import java.nio.file.*;

/**
 * Helper class for enabling DRM (Widevine) support in JCEF.
 *
 * <p>DRM playback requires:
 * <ul>
 *   <li>A valid {@code cache_path} set in {@link CefSettings} (the Widevine CDM is stored here)</li>
 *   <li>The component updater must not be disabled (do not pass {@code --disable-component-update})</li>
 *   <li>The CEF binary must include Widevine support (standard JetBrains/Spotify distributions do)</li>
 *   <li>For H.264/AAC content: the full (non-minimal) CEF distribution with proprietary codecs</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * CefSettings settings = new CefSettings();
 * DrmSupport.configureCefSettings(settings);
 *
 * CefApp.addAppHandler(new CefAppHandlerAdapter(null) {
 *     @Override
 *     public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
 *         DrmSupport.configureCommandLine(commandLine);
 *     }
 * });
 * }</pre>
 */
public class DrmSupport {

    private static final String WIDEVINE_CDM_DIR = "WidevineCdm";

    private DrmSupport() {}

    /**
     * Configures CefSettings for DRM support.
     * Sets cache_path if not already set (required for component updater to download Widevine CDM).
     *
     * @param settings the CefSettings to configure
     */
    public static void configureCefSettings(CefSettings settings) {
        if (settings.cache_path == null || settings.cache_path.isEmpty()) {
            String userHome = System.getProperty("user.home", ".");
            settings.cache_path = Paths.get(userHome, ".jcef", "cache").toString();
        }
    }

    /**
     * Configures command line switches that are helpful for DRM operation.
     * Call this from {@code CefAppHandlerAdapter.onBeforeCommandLineProcessing()}.
     *
     * @param commandLine the CEF command line to configure
     */
    public static void configureCommandLine(CefCommandLine commandLine) {
        // Ensure component update is not disabled (needed for Widevine CDM download)
        // Note: We intentionally do NOT add --disable-component-update
    }

    /**
     * Installs a pre-downloaded Widevine CDM into the cache directory.
     * Use this when the component updater cannot download the CDM (e.g., network restrictions).
     *
     * <p>The source directory must contain:
     * <ul>
     *   <li>{@code manifest.json} - CDM manifest with version info</li>
     *   <li>{@code _platform_specific/linux_x64/libwidevinecdm.so} (Linux)</li>
     *   <li>{@code _platform_specific/win_x64/widevinecdm.dll} (Windows)</li>
     *   <li>{@code _platform_specific/mac_x64/libwidevinecdm.dylib} (macOS)</li>
     * </ul>
     *
     * @param cachePath the CEF cache path (same as CefSettings.cache_path)
     * @param cdmSourceDir path to directory containing CDM files
     * @return true if installation succeeded
     */
    public static boolean installWidevine(String cachePath, Path cdmSourceDir) {
        try {
            Path manifestPath = cdmSourceDir.resolve("manifest.json");
            if (!Files.exists(manifestPath)) {
                System.err.println("[JCEF DRM] manifest.json not found in: " + cdmSourceDir);
                return false;
            }

            // Read version from manifest
            String version = readVersionFromManifest(manifestPath);
            if (version == null) {
                System.err.println("[JCEF DRM] Could not read version from manifest.json");
                return false;
            }

            // Create target directory structure
            Path targetDir = Paths.get(cachePath, WIDEVINE_CDM_DIR, version);
            Files.createDirectories(targetDir);

            // Copy all CDM files
            copyDirectory(cdmSourceDir, targetDir);

            // Also copy manifest to WidevineCdm root (CEF reads from both locations)
            Path rootManifest = Paths.get(cachePath, WIDEVINE_CDM_DIR, "manifest.json");
            Files.copy(manifestPath, rootManifest, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[JCEF DRM] Widevine CDM " + version + " installed to: " + targetDir);
            return true;
        } catch (Exception e) {
            System.err.println("[JCEF DRM] Failed to install Widevine CDM: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a Widevine CDM is installed in the given cache path.
     *
     * @param cachePath the CEF cache path
     * @return true if a CDM installation is detected
     */
    public static boolean isWidevineInstalled(String cachePath) {
        if (cachePath == null || cachePath.isEmpty()) return false;
        Path cdmDir = Paths.get(cachePath, WIDEVINE_CDM_DIR);
        if (!Files.isDirectory(cdmDir)) return false;

        // Look for any version subdirectory with a manifest.json
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cdmDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && Files.exists(entry.resolve("manifest.json"))) {
                    return true;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    private static String readVersionFromManifest(Path manifestPath) {
        try {
            String content = new String(Files.readAllBytes(manifestPath));
            // Simple JSON parsing for "version" field
            int idx = content.indexOf("\"version\"");
            if (idx < 0) return null;
            int colonIdx = content.indexOf(":", idx);
            if (colonIdx < 0) return null;
            int firstQuote = content.indexOf("\"", colonIdx);
            if (firstQuote < 0) return null;
            int secondQuote = content.indexOf("\"", firstQuote + 1);
            if (secondQuote < 0) return null;
            return content.substring(firstQuote + 1, secondQuote);
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Path targetPath = target.resolve(source.relativize(dir));
                Files.createDirectories(targetPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
