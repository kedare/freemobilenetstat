/*
 * Copyright (C) 2012 Pixmob (http://github.com/pixmob)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pixmob.hcl;

import static org.pixmob.hcl.Constants.HTTP_DELETE;
import static org.pixmob.hcl.Constants.HTTP_GET;
import static org.pixmob.hcl.Constants.HTTP_HEAD;
import static org.pixmob.hcl.Constants.HTTP_POST;
import static org.pixmob.hcl.Constants.HTTP_PUT;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;

import android.content.Context;
import android.os.Build;

/**
 * This class is used to prepare and execute an Http request.
 * @author Pixmob
 */
public final class HttpRequestBuilder {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CONTENT_CHARSET = "UTF-8";
    private static final Map<String, List<String>> NO_HEADERS = new HashMap<String, List<String>>(0);
    private static TrustManager[] trustManagers;
    private final byte[] buffer = new byte[1024];
    private final HttpClient hc;
    private String uri;
    private String method;
    private Set<Integer> expectedStatusCodes = new HashSet<Integer>(2);
    private Map<String, String> cookies;
    private Map<String, List<String>> headers;
    private Map<String, String> parameters;
    private byte[] payload;
    private boolean payloadSet;
    private String contentType;
    private HttpResponseHandler handler;

    HttpRequestBuilder(final HttpClient hc, final String uri, final String method) {
        this.hc = hc;
        this.uri = uri;
        this.method = method;
    }

    public HttpRequestBuilder expectStatusCode(int... statusCodes) {
        if (statusCodes != null) {
            for (final int statusCode : statusCodes) {
                if (statusCode < 1) {
                    throw new IllegalArgumentException("Invalid status code: " + statusCode);
                }
                expectedStatusCodes.add(statusCode);
            }
        }
        return this;
    }

    public HttpRequestBuilder setContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    public HttpRequestBuilder setPayload(byte[] payload) {
        this.payload = payload;
        payloadSet = true;
        return this;
    }

    public HttpRequestBuilder setCookies(Map<String, String> cookies) {
        this.cookies = cookies;
        return this;
    }

    public HttpRequestBuilder setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
        return this;
    }

    public HttpRequestBuilder addHeader(String name, String value) {
        if (name == null) {
            throw new IllegalArgumentException("Header name cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Header value cannot be null");
        }
        if (headers == null) {
            headers = new HashMap<String, List<String>>(2);
        }
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<String>(1);
            headers.put(name, values);
        }
        values.add(value);
        return this;
    }

    public HttpRequestBuilder setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
        return this;
    }

    public HttpRequestBuilder setParameter(String name, String value) {
        if (name == null) {
            throw new IllegalArgumentException("Parameter name cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Parameter value cannot be null");
        }
        if (parameters == null) {
            parameters = new HashMap<String, String>(4);
        }
        parameters.put(name, value);
        return this;
    }

    public HttpRequestBuilder setCookie(String name, String value) {
        if (name == null) {
            throw new IllegalArgumentException("Cookie name cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Cookie value cannot be null");
        }
        if (cookies == null) {
            cookies = new HashMap<String, String>(2);
        }
        cookies.put(name, value);
        return this;
    }

    public HttpRequestBuilder setHandler(HttpResponseHandler handler) {
        this.handler = handler;
        return this;
    }

    public HttpRequestBuilder toFile(File file) {
        setHandler(new WriteToFileHandler(file));
        return this;
    }

    public void execute() throws HttpClientException {
        HttpURLConnection conn = null;
        UncloseableInputStream payloadStream = null;
        try {
            if (parameters != null && !parameters.isEmpty()) {
                final StringBuilder buf = new StringBuilder(256);
                if (HTTP_GET.equals(method) || HTTP_HEAD.equals(method)) {
                    buf.append('?');
                }

                int paramIdx = 0;
                for (final Map.Entry<String, String> e : parameters.entrySet()) {
                    if (paramIdx != 0) {
                        buf.append("&");
                    }
                    final String name = e.getKey();
                    final String value = e.getValue();
                    buf.append(URLEncoder.encode(name, CONTENT_CHARSET)).append("=")
                            .append(URLEncoder.encode(value, CONTENT_CHARSET));
                    ++paramIdx;
                }

                if (!payloadSet
                        && (HTTP_POST.equals(method) || HTTP_DELETE.equals(method) || HTTP_PUT.equals(method))) {
                    try {
                        payload = buf.toString().getBytes(CONTENT_CHARSET);
                    } catch (UnsupportedEncodingException e) {
                        // Unlikely to happen.
                        throw new HttpClientException("Encoding error", e);
                    }
                } else {
                    uri += buf;
                }
            }

            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(hc.getConnectTimeout());
            conn.setReadTimeout(hc.getReadTimeout());
            conn.setAllowUserInteraction(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setUseCaches(false);
            conn.setDoInput(true);

            if (headers != null && !headers.isEmpty()) {
                for (final Map.Entry<String, List<String>> e : headers.entrySet()) {
                    final List<String> values = e.getValue();
                    if (values != null) {
                        final String name = e.getKey();
                        for (final String value : values) {
                            conn.addRequestProperty(name, value);
                        }
                    }
                }
            }

            if (cookies != null && !cookies.isEmpty() || hc.getInMemoryCookies() != null
                    && !hc.getInMemoryCookies().isEmpty()) {
                final StringBuilder cookieHeaderValue = new StringBuilder(256);
                prepareCookieHeader(cookies, cookieHeaderValue);
                prepareCookieHeader(hc.getInMemoryCookies(), cookieHeaderValue);
                conn.setRequestProperty("Cookie", cookieHeaderValue.toString());
            }

            final String userAgent = hc.getUserAgent();
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            }

            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Location", uri);
            conn.setRequestProperty("Referrer", uri);
            conn.setRequestProperty("Accept-Encoding", "gzip,deflate");
            conn.setRequestProperty("Accept-Charset", CONTENT_CHARSET);

            if (conn instanceof HttpsURLConnection) {
                setupSecureConnection(hc.getContext(), (HttpsURLConnection) conn);
            }

            if (HTTP_POST.equals(method) || HTTP_DELETE.equals(method) || HTTP_PUT.equals(method)) {
                if (payload != null) {
                    conn.setDoOutput(true);
                    if (!payloadSet) {
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset="
                                + CONTENT_CHARSET);
                    } else if (contentType != null) {
                        conn.setRequestProperty("Content-Type", contentType);
                    }
                    conn.setFixedLengthStreamingMode(payload.length);

                    final OutputStream out = conn.getOutputStream();
                    out.write(payload);
                    out.flush();
                } else {
                    conn.setFixedLengthStreamingMode(0);
                }
            }

            conn.connect();

            final int statusCode = conn.getResponseCode();
            if (statusCode == -1) {
                throw new HttpClientException("Invalid response from " + uri);
            }
            if (!expectedStatusCodes.isEmpty() && !expectedStatusCodes.contains(statusCode)) {
                throw new HttpClientException("Expected status code " + expectedStatusCodes + ", got "
                        + statusCode);
            } else if (expectedStatusCodes.isEmpty() && statusCode / 100 != 2) {
                throw new HttpClientException("Expected status code 2xx, got " + statusCode);
            }

            final Map<String, List<String>> headerFields = conn.getHeaderFields();
            final Map<String, String> inMemoryCookies = hc.getInMemoryCookies();
            if (headerFields != null) {
                final List<String> newCookies = headerFields.get("Set-Cookie");
                if (newCookies != null) {
                    for (final String newCookie : newCookies) {
                        final String rawCookie = newCookie.split(";", 2)[0];
                        final int i = rawCookie.indexOf('=');
                        final String name = rawCookie.substring(0, i);
                        final String value = rawCookie.substring(i + 1);
                        inMemoryCookies.put(name, value);
                    }
                }
            }

            payloadStream = new UncloseableInputStream(getInputStream(conn));
            if (handler != null) {
                final HttpResponse resp = new HttpResponse(statusCode, payloadStream,
                        headerFields == null ? NO_HEADERS : headerFields, inMemoryCookies);
                try {
                    handler.onResponse(resp);
                } catch (Exception e) {
                    throw new HttpClientException("Error in response handler", e);
                }
            }
        } catch (SocketTimeoutException e) {
            if (handler != null) {
                try {
                    handler.onTimeout();
                } catch (Exception e2) {
                    throw new HttpClientException("Error in response handler", e2);
                }
            } else {
                throw new HttpClientException("Response timeout from " + uri, e);
            }
        } catch (IOException e) {
            throw new HttpClientException("Connection failed to " + uri, e);
        } finally {
            if (conn != null) {
                if (payloadStream != null) {
                    // Fully read Http response:
                    // http://docs.oracle.com/javase/6/docs/technotes/guides/net/http-keepalive.html
                    try {
                        while (payloadStream.read(buffer) != -1) {
                            ;
                        }
                    } catch (IOException ignore) {
                    }
                    payloadStream.forceClose();
                }
                conn.disconnect();
            }
        }
    }

    private static void prepareCookieHeader(Map<String, String> cookies, StringBuilder headerValue) {
        if (cookies != null) {
            for (final Map.Entry<String, String> e : cookies.entrySet()) {
                if (headerValue.length() != 0) {
                    headerValue.append("; ");
                }
                headerValue.append(e.getKey()).append("=").append(e.getValue());
            }
        }
    }

    /**
     * Open the {@link InputStream} of an Http response. This method supports
     * GZIP and DEFLATE responses.
     */
    private static InputStream getInputStream(HttpURLConnection conn) throws IOException {
        final List<String> contentEncodingValues = conn.getHeaderFields().get("Content-Encoding");
        if (contentEncodingValues != null) {
            for (final String contentEncoding : contentEncodingValues) {
                if (contentEncoding != null) {
                    if (contentEncoding.contains("gzip")) {
                        return new GZIPInputStream(conn.getInputStream());
                    }
                    if (contentEncoding.contains("deflate")) {
                        return new InflaterInputStream(conn.getInputStream(), new Inflater(true));
                    }
                }
            }
        }
        return conn.getInputStream();
    }

    private static KeyStore loadCertificates(Context context) throws IOException {
        try {
            final KeyStore localTrustStore = KeyStore.getInstance("BKS");
            final InputStream in = context.getResources().openRawResource(R.raw.hcl_keystore);
            try {
                localTrustStore.load(in, null);
            } finally {
                in.close();
            }

            return localTrustStore;
        } catch (Exception e) {
            final IOException ioe = new IOException("Failed to load SSL certificates");
            ioe.initCause(e);
            throw ioe;
        }
    }

    /**
     * Setup SSL connection.
     */
    private static void setupSecureConnection(Context context, HttpsURLConnection conn) throws IOException {
        final SSLContext sslContext;
        try {
            // SSL certificates are provided by the Guardian Project:
            // https://github.com/guardianproject/cacert
            if (trustManagers == null) {
                // Load SSL certificates:
                // http://nelenkov.blogspot.com/2011/12/using-custom-certificate-trust-store-on.html
                // Earlier Android versions do not have updated root CA
                // certificates, resulting in connection errors.
                final KeyStore keyStore = loadCertificates(context);

                final CustomTrustManager customTrustManager = new CustomTrustManager(keyStore);
                trustManagers = new TrustManager[] { customTrustManager };
            }

            // Init SSL connection with custom certificates.
            // The same SecureRandom instance is used for every connection to
            // speed up initialization.
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, SECURE_RANDOM);
        } catch (GeneralSecurityException e) {
            final IOException ioe = new IOException("Failed to initialize SSL engine");
            ioe.initCause(e);
            throw ioe;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            // Fix slow read:
            // http://code.google.com/p/android/issues/detail?id=13117
            // Prior to ICS, the host name is still resolved even if we already
            // know its IP address, for each connection.
            final SSLSocketFactory delegate = sslContext.getSocketFactory();
            final SSLSocketFactory socketFactory = new SSLSocketFactory() {
                @Override
                public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
                    InetAddress addr = InetAddress.getByName(host);
                    injectHostname(addr, host);
                    return delegate.createSocket(addr, port);
                }

                @Override
                public Socket createSocket(InetAddress host, int port) throws IOException {
                    return delegate.createSocket(host, port);
                }

                @Override
                public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                        throws IOException, UnknownHostException {
                    return delegate.createSocket(host, port, localHost, localPort);
                }

                @Override
                public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                        int localPort) throws IOException {
                    return delegate.createSocket(address, port, localAddress, localPort);
                }

                private void injectHostname(InetAddress address, String host) {
                    try {
                        Field field = InetAddress.class.getDeclaredField("hostName");
                        field.setAccessible(true);
                        field.set(address, host);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                        throws IOException {
                    injectHostname(s.getInetAddress(), host);
                    return delegate.createSocket(s, host, port, autoClose);
                }

                @Override
                public String[] getDefaultCipherSuites() {
                    return delegate.getDefaultCipherSuites();
                }

                @Override
                public String[] getSupportedCipherSuites() {
                    return delegate.getSupportedCipherSuites();
                }
            };
            conn.setSSLSocketFactory(socketFactory);
        } else {
            conn.setSSLSocketFactory(sslContext.getSocketFactory());
        }

        conn.setHostnameVerifier(new BrowserCompatHostnameVerifier());
    }
}
