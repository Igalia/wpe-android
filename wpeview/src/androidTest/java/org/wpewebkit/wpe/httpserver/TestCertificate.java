/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpe.httpserver;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The certificate of the test server over TLS, for localhost and 127.0.0.1, which lasts until 2126. It signs itself,
 * and its key is public, so it is only good for tests. Made with:
 *
 * <pre>
 * openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout key.pem -out cert.pem \
 *     -days 36500 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
 * openssl pkcs8 -topk8 -nocrypt -in key.pem
 * </pre>
 */
final class TestCertificate {
    private static final String KEY = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgZOaZI0BI7MWVF0fF"
                                      + "Hvl//F5DsfUvZ+EW/y3u5afirkuhRANCAAQsgSOBHBK1io/bhOu+uE6lfrilZ7JV"
                                      + "sQqYqjhj2dG+dw78z8ktk6AQdVg++f7DnzpVUpD19oUrb13/K3AxylkK";

    private static final String CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
                                              + "MIIBeTCCASCgAwIBAgIUCHySaE7WiDYF/wLbN/0H1Vh01/UwCgYIKoZIzj0EAwIw\n"
                                              + "FDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MTExNzA4NDYxNVoYDzIxMjYxMDI0\n"
                                              + "MDg0NjE1WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwWTATBgcqhkjOPQIBBggqhkjO\n"
                                              + "PQMBBwNCAAQsgSOBHBK1io/bhOu+uE6lfrilZ7JVsQqYqjhj2dG+dw78z8ktk6AQ\n"
                                              + "dVg++f7DnzpVUpD19oUrb13/K3AxylkKo04wTDAdBgNVHQ4EFgQU5tav73dvNqLl\n"
                                              + "WZv4NBp8kY2Z4NUwDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhv\n"
                                              + "c3SHBH8AAAEwCgYIKoZIzj0EAwIDRwAwRAIgH71FcsT4wydpvO11ZskvExwKE2wx\n"
                                              + "Lini9QdVbNCrySQCIEv4UOuFf53FX0bYK8dqAUcXhYw11zRoUR4sZjpqSoRm\n"
                                              + "-----END CERTIFICATE-----\n";

    private TestCertificate() {}

    static ServerSocketFactory serverSocketFactory() throws Exception {
        PrivateKey key =
            KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(KEY, Base64.DEFAULT)));
        Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            new ByteArrayInputStream(CERTIFICATE.getBytes("US-ASCII")));

        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", key, password, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context.getServerSocketFactory();
    }
}
