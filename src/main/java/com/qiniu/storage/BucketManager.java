package com.qiniu.storage;

import com.qiniu.common.Config;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Client;
import com.qiniu.http.Response;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import com.qiniu.util.StringUtils;
import com.qiniu.util.UrlSafeBase64;

import java.util.ArrayList;
import java.util.Iterator;

public final class BucketManager {
    private final Auth auth;
    private final Client client;

    public BucketManager(Auth auth) {
        this.auth = auth;
        client = new Client();
    }

    public static String entry(String bucket, String key) {
        String en = bucket;
        if (key != null) {
            en = bucket + ":" + key;
        }
        return UrlSafeBase64.encodeToString(en);
    }

    /**
     * @return bucket 列表
     */
    public String[] buckets() throws QiniuException {
        Response r = rsGet("/buckets");
        return r.jsonToObject(String[].class);
    }

    public FileListIterator createFileListIterator(String bucket, String prefix) {
        return new FileListIterator(bucket, prefix, 1000, null);
    }

    public FileListIterator createFileListIterator(String bucket, String prefix, int limit, String delimiter) {
        return new FileListIterator(bucket, prefix, limit, delimiter);
    }

    public FileListing listFiles(String bucket, String prefix, String marker, int limit, String delimiter)
            throws QiniuException {
        StringMap map = new StringMap().put("bucket", bucket).putNotEmpty("marker", marker)
                .putNotEmpty("prefix", prefix).putNotEmpty("delimiter", delimiter).putWhen("limit", limit, limit > 0);

        String url = Config.RSF_HOST + "/list?" + map.formString();
        Response r = get(url);
        return r.jsonToObject(FileListing.class);
    }

    public FileInfo stat(String bucket, String key) throws QiniuException {
        Response r = rsGet("/stat/" + entry(bucket, key));
        return r.jsonToObject(FileInfo.class);
    }

    public void delete(String bucket, String key) throws QiniuException {
        rsPost("/delete/" + entry(bucket, key));
    }

    public void rename(String bucket, String oldname, String newname) throws QiniuException {
        move(bucket, oldname, bucket, newname);
    }

    public void copy(String from_bucket, String from_key, String to_bucket, String to_key) throws QiniuException {
        String from = entry(from_bucket, from_key);
        String to = entry(to_bucket, to_key);
        String path = "/copy/" + from + "/" + to;
        rsPost(path);
    }

    public void move(String from_bucket, String from_key, String to_bucket, String to_key) throws QiniuException {
        String from = entry(from_bucket, from_key);
        String to = entry(to_bucket, to_key);
        String path = "/move/" + from + "/" + to;
        rsPost(path);
    }

    public void changeMime(String bucket, String key, String mime) throws QiniuException {
        String resource = entry(bucket, key);
        String encode_mime = UrlSafeBase64.encodeToString(mime);
        String path = "/chgm/" + resource + "/mime/" + encode_mime;
        rsPost(path);
    }

    public void fetch(String url, String bucket, String key) throws QiniuException {
        String resource = UrlSafeBase64.encodeToString(url);
        String to = entry(bucket, key);
        String path = "/fetch/" + resource + "/to/" + to;
        ioPost(path);
    }

    public void prefetch(String bucket, String key) throws QiniuException {
        String resource = entry(bucket, key);
        String path = "/prefetch/" + resource;
        ioPost(path);
    }

    public Response batch(Batch operations) throws QiniuException {
        return rsPost("/batch", operations.toBody());
    }

    private Response rsPost(String path, byte[] body) throws QiniuException {
        String url = Config.RS_HOST + path;
        return post(url, body);
    }

    private Response rsPost(String path) throws QiniuException {
        return rsPost(path, null);
    }

    private Response rsGet(String path) throws QiniuException {
        String url = Config.RS_HOST + path;
        return get(url);
    }

    private Response ioPost(String path) throws QiniuException {
        String url = Config.IO_HOST + path;
        return post(url, null);
    }

    private Response get(String url) throws QiniuException {
        StringMap headers = auth.authorization(url);
        return client.get(url, headers);
    }

    private Response post(String url, byte[] body) throws QiniuException {
        StringMap headers = auth.authorization(url, body, Client.FormMime);
        return client.post(url, body, headers, Client.FormMime);
    }

    public static Batch createBatch() {
        return new Batch();
    }

    public static class Batch {
        private ArrayList<String> ops;

        private Batch() {
            this.ops = new ArrayList<String>();
        }

        public Batch copy(String from_bucket, String from_key, String to_bucket, String to_key) {
            String from = entry(from_bucket, from_key);
            String to = entry(to_bucket, to_key);
            ops.add("copy" + "/" + from + "/" + to);
            return this;
        }

        public Batch rename(String from_bucket, String from_key, String to_key) {
            return move(from_bucket, from_key, from_bucket, to_key);
        }

        public Batch move(String from_bucket, String from_key, String to_bucket, String to_key) {
            String from = entry(from_bucket, from_key);
            String to = entry(to_bucket, to_key);
            ops.add("move" + "/" + from + "/" + to);
            return this;
        }

        public Batch delete(String bucket, String... keys) {
            for (String key : keys) {
                ops.add("delete" + "/" + entry(bucket, key));
            }
            return this;
        }

        public Batch stat(String bucket, String... keys) {
            for (String key : keys) {
                ops.add("stat" + "/" + entry(bucket, key));
            }
            return this;
        }

        public byte[] toBody() {
            String body = StringUtils.join(ops, "&op=", "op=");
            return StringUtils.utf8Bytes(body);
        }
    }

    public class FileListIterator implements Iterator<FileInfo[]> {
        private String marker = null;
        private String bucket;
        private String delimiter;
        private int limit;
        private String prefix;
        private QiniuException exception = null;

        public FileListIterator(String bucket, String prefix, int limit, String delimiter) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must great than 0");
            }
            this.bucket = bucket;
            this.prefix = prefix;
            this.limit = limit;
            this.delimiter = delimiter;
        }

        public QiniuException error() {
            return exception;
        }

        @Override
        public boolean hasNext() {
            return exception == null && !"".equals(marker);
        }

        @Override
        public FileInfo[] next() {
            try {
                FileListing f = listFiles(bucket, prefix, marker, limit, delimiter);
                this.marker = f.marker == null ? "" : f.marker;
                return f.items;
            } catch (QiniuException e) {
                this.exception = e;
                return null;
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }
}
