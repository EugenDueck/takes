/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2019 Yegor Bugayenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.takes.http;

import com.jcabi.http.request.JdkRequest;
import com.jcabi.http.response.RestResponse;
import com.jcabi.matchers.RegexMatchers;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.IOUtils;
import org.cactoos.io.BytesOf;
import org.cactoos.text.Joined;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Test;
import org.takes.Request;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.rq.RqHeaders;
import org.takes.rq.RqLengthAware;
import org.takes.rq.RqSocket;
import org.takes.rs.ResponseOf;
import org.takes.tk.TkText;

/**
 * Test case for {@link BkBasic}.
 *
 * @since 0.15.2
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle MultipleStringLiteralsCheck (500 lines)
 */
@SuppressWarnings(
    {
        "PMD.ExcessiveImports",
        "PMD.TooManyMethods"
    })
public final class BkBasicTest {

    /**
     * Carriage return constant.
     */
    private static final String CRLF = "\r\n";

    /**
     * POST header constant.
     */
    private static final String POST = "POST / HTTP/1.1";

    /**
     * Host header constant.
     */
    private static final String HOST = "Host:localhost";

    /**
     * BkBasic can handle socket data.
     *
     * @throws Exception If some problem inside
     */
    @Test
    public void handlesSocket() throws Exception {
        final MkSocket socket = BkBasicTest.createMockSocket();
        final ByteArrayOutputStream baos = socket.bufferedOutput();
        final String hello = "Hello World";
        new BkBasic(new TkText(hello)).accept(socket);
        MatcherAssert.assertThat(
            baos.toString(),
            Matchers.containsString(hello)
        );
    }

    /**
     * BkBasic can return HTTP status 404 when accessing invalid URL.
     *
     * @throws Exception if any I/O error occurs.
     */
    @Test
    public void returnsProperResponseCodeOnInvalidUrl() throws Exception {
        new FtRemote(
            new TkFork(
                new FkRegex("/path/a", new TkText("a")),
                new FkRegex("/path/b", new TkText("b"))
            )
        ).exec(
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    new JdkRequest(String.format("%s/path/c", home))
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_NOT_FOUND);
                }
            }
        );
    }

    /**
     * BkBasic produces headers with addresses without slashes.
     *
     * @throws Exception If some problem inside
     */
    @Test
    public void addressesInHeadersAddedWithoutSlashes() throws Exception {
        final Socket socket = BkBasicTest.createMockSocket();
        final AtomicReference<Request> ref = new AtomicReference<>();
        new BkBasic(
            req -> {
                ref.set(req);
                return new ResponseOf(
                    () -> Collections.singletonList("HTTP/1.1 200 OK"),
                    req::body
                );
            }
        ).accept(socket);
        final Request request = ref.get();
        final RqHeaders.Smart smart = new RqHeaders.Smart(
            new RqHeaders.Base(request)
        );
        MatcherAssert.assertThat(
            smart.single(
                "X-Takes-LocalAddress",
                ""
            ),
            Matchers.not(
                Matchers.containsString("/")
            )
        );
        MatcherAssert.assertThat(
            smart.single(
                "X-Takes-RemoteAddress",
                ""
            ),
            Matchers.not(
                Matchers.containsString("/")
            )
        );
        MatcherAssert.assertThat(
            new RqSocket(request).getLocalAddress(),
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            new RqSocket(request).getRemoteAddress(),
            Matchers.notNullValue()
        );
    }

    private static void consume(InputStream input) throws IOException {
        try (OutputStream output = new ByteArrayOutputStream()) {
            IOUtils.copy(input, output);
        }
    }

    /**
     * BkBasic can handle two requests in one connection.
     *
     * @throws Exception If some problem inside
     */
    @Test
    public void handlesTwoRequestInOneConnection() throws Exception {
        final String text = "Hello Twice!";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ServerSocket server = new ServerSocket(0)) {
            new Thread(
                () -> {
                    try {
                        new BkBasic(req -> {
                            // we need to consume the request body to resolve the deadlock between RqLive.parse() trying
                            // to read the next byte from the socket in the line
                            // data = RqLive.data(input, new Opt.Empty<>());
                            // and the for loop further down in this test preventing the socket from closing on the
                            // client side (which would lead to an EOS being read by RqLive.parse(), making the server
                            // close the connection)
                            consume(new RqLengthAware(req).body());
                            return new TkText(text).act(req);
                        }).accept(
                            server.accept()
                        );
                    } catch (final IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            ).start();
            try (Socket socket = new Socket(
                server.getInetAddress(),
                server.getLocalPort()
            )
            ) {
                socket.getOutputStream().write(
                    new Joined(
                        BkBasicTest.CRLF,
                        BkBasicTest.POST,
                        BkBasicTest.HOST,
                        "Content-Length: 11",
                        "",
                        "Hello First",
                        BkBasicTest.POST,
                        BkBasicTest.HOST,
                        "Content-Length: 12",
                        "",
                        "Hello Second"
                    ).asString().getBytes()
                );
                final InputStream input = socket.getInputStream();
                // @checkstyle MagicNumber (1 line)
                final byte[] buffer = new byte[4096];
                for (int count = input.read(buffer); count != -1;
                    count = input.read(buffer)) {
                    output.write(buffer, 0, count);
                }
            }
        }
        MatcherAssert.assertThat(
            output.toString(),
            RegexMatchers.containsPattern(
                String.format("(?s)%s.*?%s", text, text)
            )
        );
    }

    /**
     * BkBasic can return HTTP status 411 when a persistent connection request
     * has no Content-Length.
     *
     * @throws Exception If some problem inside
     */
    @Ignore
    @Test
    public void returnsProperResponseCodeOnNoContentLength() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final String text = "Say hello!";
        try (ServerSocket server = new ServerSocket(0)) {
            new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new BkBasic(new TkText("411 Test")).accept(
                                server.accept()
                            );
                        } catch (final IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                }
            ).start();
            try (Socket socket = new Socket(
                server.getInetAddress(),
                server.getLocalPort()
            )
            ) {
                socket.getOutputStream().write(
                    new BytesOf(
                        new Joined(
                            BkBasicTest.CRLF,
                            BkBasicTest.POST,
                            BkBasicTest.HOST,
                            "",
                            text
                        )
                    ).asBytes()
                );
                final InputStream input = socket.getInputStream();
                // @checkstyle MagicNumber (1 line)
                final byte[] buffer = new byte[4096];
                for (int count = input.read(buffer); count != -1;
                    count = input.read(buffer)) {
                    output.write(buffer, 0, count);
                }
            }
        }
        MatcherAssert.assertThat(
            output.toString(),
            Matchers.containsString("HTTP/1.1 411 Length Required")
        );
    }

    /**
     * BkBasic can accept no content-length on closed connection.
     *
     * @throws Exception If some problem inside
     */
    @Ignore
    @Test
    public void acceptsNoContentLengthOnClosedConnection() throws Exception {
        final String text = "Close Test";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final String greetings = "Hi everyone";
        try (ServerSocket server = new ServerSocket(0)) {
            new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new BkBasic(new TkText(text)).accept(
                                server.accept()
                            );
                        } catch (final IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                }
            ).start();
            try (Socket socket = new Socket(
                server.getInetAddress(),
                server.getLocalPort()
            )
            ) {
                socket.getOutputStream().write(
                    new BytesOf(
                        new Joined(
                            BkBasicTest.CRLF,
                            BkBasicTest.POST,
                            BkBasicTest.HOST,
                            "Connection: Close",
                            "",
                            greetings
                        )
                    ).asBytes()
                );
                final InputStream input = socket.getInputStream();
                // @checkstyle MagicNumber (1 line)
                final byte[] buffer = new byte[4096];
                for (int count = input.read(buffer); count != -1;
                    count = input.read(buffer)) {
                    output.write(buffer, 0, count);
                }
            }
        }
        MatcherAssert.assertThat(
            output.toString(),
            Matchers.containsString(text)
        );
    }

    /**
     * Creates Socket mock for reuse.
     *
     * @return Prepared Socket mock
     * @throws Exception If some problem inside
     */
    private static MkSocket createMockSocket() throws Exception {
        return new MkSocket(
            new ByteArrayInputStream(
                new BytesOf(
                    new Joined(
                        BkBasicTest.CRLF,
                        "GET / HTTP/1.1",
                        BkBasicTest.HOST,
                        "Content-Length: 2",
                        "",
                        "hi"
                    )
                ).asBytes()
            )
        );
    }
}
