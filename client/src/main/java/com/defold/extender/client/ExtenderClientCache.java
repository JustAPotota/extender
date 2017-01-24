package com.defold.extender.client;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.*;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import java.security.MessageDigest;

public class ExtenderClientCache {

    private static final String hashFn = "SHA-256";
    private static final String cacheFile = ".buildcache";

    private HashMap<String, Timestamp> timestamps = new HashMap<>();        // Time stamps for input files
    private HashMap<String, String> hashes = new HashMap<>();               // Hashes for input files
    private HashMap<String, String> persistentHashes = new HashMap<>();     // Only the build artifacts

    private final File cacheDir;

    public ExtenderClientCache(File cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        if (!this.cacheDir.exists()) {
            throw new IOException("Cache directory does not exist: " + cacheDir.getAbsolutePath());
        }
        if (!this.cacheDir.isDirectory()) {
            throw new IOException("Wanted cache directory is not a directory: " + cacheDir.getAbsolutePath());
        }
        loadCache();
    }

    /** Calculates (if needed) a hash from a file (Public for unit tests)
     */
    public String getHash(File file) {
        String path = file.getAbsolutePath();
        Timestamp fileTimestamp = new Timestamp(file.lastModified());
        Timestamp timestamp = this.timestamps.getOrDefault(path, null);

        if (timestamp != null && fileTimestamp.equals(timestamp) ) {
            String hash = this.hashes.getOrDefault(path, null);
            if (hash != null) {
                return hash;
            }
        }

        // Create a new hash
        String hash = ExtenderClientCache.hash(file);
        this.timestamps.put(path, fileTimestamp);
        this.hashes.put(path, hash);
        return hash;
    }

    private void getHash(List<File> files, MessageDigest md) {
        if (files.isEmpty()) {
            throw new RuntimeException("The list of files must not be empty");
        }

        files.sort(new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getAbsolutePath().compareTo(o2.getAbsolutePath());
            }
        });

        for (File file : files) {
            String fileHash = getHash(file);
            md.update(fileHash.getBytes());
        }
    }

    /** Gets the combined hash from a list of files (Public for unit tests)
     */
    public String getHash(List<File> files) {
        MessageDigest md = ExtenderClientCache.getHasher();
        getHash(files, md);
        return hashToString(md.digest());
    }

    /** Gets the platform specific build artifact filename
     */
    public File getCachedBuildFile(String platform) {
        return new File(cacheDir + File.separator + platform + File.separator + "build.zip" );
    }

    /** Gets the cache storage file
     */
    public File getCacheFile() {
        return new File(cacheDir.getAbsolutePath() + File.separator + this.cacheFile);
    }

    /** Checks if a cached build is still valid.
     * @return If the cached version is still valid, returns that file
     */
    public File isCachedBuildValid(String platform, String sdkVersion, List<File> files) {
        File f = getCachedBuildFile(platform);
        if (!f.exists()) {
            return null;
        }

        String previousHash = this.persistentHashes.getOrDefault(f.getAbsolutePath(), null);
        String inputHash = calcKey(platform, sdkVersion, files);
        return inputHash.equals(previousHash) ? f : null;
    }

    /** Calculates a key to identify a build
     * @param platform      E.g. "armv7-ios"
     * @param sdkVersion    A sha1 of the defold sdk (i.e. engine version)
     * @param files         A list of files affecting the build
     * @return The calculated key
     */
    public String calcKey(String platform, String sdkVersion, List<File> files) {
        MessageDigest md = ExtenderClientCache.getHasher();
        md.update(platform.getBytes());
        md.update(sdkVersion.getBytes());
        getHash(files, md);
        return hashToString(md.digest());
    }

    /** After a successful build, the client has to store the "key" in the cache.
     * This will persist the cache between sessions
     * @param platform
     * @param sdkVersion
     * @param files
     */
    public void storeCachedBuild(String platform, String sdkVersion, List<File> files) {
        String key = calcKey(platform, sdkVersion, files);
        File f = getCachedBuildFile(platform);
        this.persistentHashes.put(f.getAbsolutePath(), key);
        saveCache();
    }

    //

    private static MessageDigest getHasher() {
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance(hashFn);
        } catch(Exception e){
            throw new RuntimeException("Could not create hash function: " + hashFn, e);
        }
        return md;
    }

    private static String hashToString(byte[] digest) {
        return (new HexBinaryAdapter()).marshal(digest);
    }

    private static String hash(File file) {
        try{
            MessageDigest md = ExtenderClientCache.getHasher();
            byte[] data = Files.readAllBytes(file.toPath());
            md.update(data);
            return hashToString(md.digest());
        } catch(Exception e){
            throw new RuntimeException(e);
        }
    }


    private void saveCache() {
        Properties properties = new Properties();
        properties.putAll(this.persistentHashes);
        try {
            properties.store(new FileOutputStream(getCacheFile()), null);
        } catch (IOException e) {
            System.out.println(String.format("Could not store cache to '%s'", getCacheFile().getAbsolutePath()));
        }
    }

    private void loadCache() {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(getCacheFile()));
        } catch (IOException e) {
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            this.persistentHashes.put(key, properties.get(key).toString());
        }
    }
}