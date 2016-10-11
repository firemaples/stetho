package com.facebook.stetho.httpclient;

import com.facebook.stetho.inspector.network.DefaultResponseHandler;
import com.facebook.stetho.inspector.network.NetworkEventReporter;
import com.facebook.stetho.inspector.network.NetworkEventReporterImpl;
import com.facebook.stetho.inspector.network.RequestBodyHelper;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.entity.BasicHttpEntityHC4;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * use:
 * <pre>
 *   HttpClientBuilder builder = HttpClients.custom();
 *   StethoInterceptor.setup(builder);
 *   builder.build();
 * </pre>
 *
 * Created by Firemaples on 08/10/2016.
 */

public class StethoInterceptor {
    private final NetworkEventReporter mEventReporter = NetworkEventReporterImpl.get();
    private final AtomicInteger mNextRequestId = new AtomicInteger(0);
    private RequestBodyHelper requestBodyHelper;

    private HashMap<HttpContext, HttpRequest> httpRequestHashMap = new HashMap<>();
    private HashMap<HttpContext, String> requestIdHashMap = new HashMap<>();

    public static void setup(HttpClientBuilder clientBuilder) {
        new StethoInterceptor(clientBuilder);
    }

    protected StethoInterceptor(HttpClientBuilder clientBuilder) {

        HttpRequestInterceptor requestInterceptor = new HttpRequestInterceptor() {
            @Override
            public void process(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
                if (!mEventReporter.isEnabled()) {
                    return;
                }
                String requestId = String.valueOf(mNextRequestId.getAndIncrement());
                requestIdHashMap.put(httpContext, requestId);

                requestBodyHelper = new RequestBodyHelper(mEventReporter, requestId);
                HttpClientInspectorRequest inspectorRequest =
                        new HttpClientInspectorRequest(requestId, httpRequest, requestBodyHelper);
                mEventReporter.requestWillBeSent(inspectorRequest);

                httpRequestHashMap.put(httpContext, httpRequest);
            }
        };

        HttpResponseInterceptor responseInterceptor = new HttpResponseInterceptor() {
            @Override
            public void process(HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
                if (!mEventReporter.isEnabled()) {
                    return;
                }
                if (requestBodyHelper != null && requestBodyHelper.hasBody()) {
                    requestBodyHelper.reportDataSent();
                }

                String requestId = requestIdHashMap.get(httpContext);
                HttpRequest httpRequest = httpRequestHashMap.get(httpContext);

                mEventReporter.responseHeadersReceived(
                        new HttpClientInspectorResponse(
                                requestId,
                                httpRequest,
                                httpResponse,
                                httpContext));

                String contentType = null;
                InputStream responseStream = null;
                String contentEncoding = null;

                HttpEntity httpEntity = httpResponse.getEntity();
                if (httpEntity != null) {
                    if (httpEntity.getContentType() != null) {
                        contentType = httpEntity.getContentType().getValue();
                    }
                    responseStream = httpEntity.getContent();
                    if (httpEntity.getContentEncoding() != null) {
                        contentEncoding = httpEntity.getContentEncoding().getValue();
                    }
                }

                responseStream = mEventReporter.interpretResponseStream(
                        requestId,
                        contentType,
                        contentEncoding,
                        responseStream,
                        new DefaultResponseHandler(mEventReporter, requestId));

                if (responseStream != null && httpEntity != null) {
                    BasicHttpEntityHC4 basicHttpEntityHC4 = new BasicHttpEntityHC4();
                    basicHttpEntityHC4.setContentEncoding(httpEntity.getContentEncoding());
                    basicHttpEntityHC4.setContentType(httpEntity.getContentType());
                    basicHttpEntityHC4.setContent(responseStream);
                    basicHttpEntityHC4.setChunked(httpEntity.isChunked());

                    httpResponse.setEntity(basicHttpEntityHC4);
                }

                requestIdHashMap.remove(httpContext);
                httpRequestHashMap.remove(httpContext);
            }
        };

        clientBuilder.addInterceptorFirst(requestInterceptor);
        clientBuilder.addInterceptorFirst(responseInterceptor);
    }

    private static class HttpClientInspectorRequest implements NetworkEventReporter.InspectorRequest {
        private final String mRequestId;
        private final HttpRequest mRequest;
        private RequestBodyHelper mRequestBodyHelper;

        HttpClientInspectorRequest(
                String requestId,
                HttpRequest request,
                RequestBodyHelper requestBodyHelper) {
            mRequestId = requestId;
            mRequest = request;
            mRequestBodyHelper = requestBodyHelper;
        }

        @Override
        public String id() {
            return mRequestId;
        }

        @Override
        public String friendlyName() {
            // Hmm, can we do better?  tag() perhaps?
            return null;
        }

        @Nullable
        @Override
        public Integer friendlyNameExtra() {
            return null;
        }

        @Override
        public String url() {
            return mRequest.getRequestLine().getUri();
        }

        @Override
        public String method() {
            return mRequest.getRequestLine().getMethod();
        }

        @Nullable
        @Override
        public byte[] body() throws IOException {
            if (mRequest instanceof HttpEntityEnclosingRequest) { //test if request is a POST
                HttpEntity entity = ((HttpEntityEnclosingRequest) mRequest).getEntity();
//                byte[] body = EntityUtils.toByteArray(entity);

                OutputStream out = mRequestBodyHelper.createBodySink(firstHeaderValue("Content-Encoding"));
                entity.writeTo(out);
                return mRequestBodyHelper.getDisplayBody();
            }
            return null;
        }

        @Override
        public int headerCount() {
            return mRequest.getAllHeaders().length;
        }

        @Override
        public String headerName(int index) {
            Header header = mRequest.getAllHeaders()[index];
            return header == null ? null : header.getName();
        }

        @Override
        public String headerValue(int index) {
            Header header = mRequest.getAllHeaders()[index];
            return header == null ? null : header.getValue();
        }

        @Nullable
        @Override
        public String firstHeaderValue(String name) {
            Header header = mRequest.getFirstHeader(name);
            return header == null ? null : header.getValue();
        }
    }

    private static class HttpClientInspectorResponse implements NetworkEventReporter.InspectorResponse {
        private final String mRequestId;
        private final HttpRequest mRequest;
        private final HttpResponse mResponse;
        private final HttpContext mHttpContext;

        HttpClientInspectorResponse(
                String requestId,
                HttpRequest request,
                HttpResponse response,
                HttpContext httpContext) {
            mRequestId = requestId;
            mRequest = request;
            mResponse = response;
            mHttpContext = httpContext;
        }

        @Override
        public String requestId() {
            return mRequestId;
        }

        @Override
        public String url() {
            return mRequest.getRequestLine().getUri();
        }

        @Override
        public int statusCode() {
            return mResponse.getStatusLine().getStatusCode();
        }

        @Override
        public String reasonPhrase() {
            return mResponse.getStatusLine().getReasonPhrase();
        }

        @Override
        public boolean connectionReused() {
            // Not sure...
            return false;
        }

        @Override
        public int connectionId() {
            return mHttpContext.hashCode();
        }

        @Override
        public boolean fromDiskCache() {
//            return mResponse.cacheResponse() != null;
            return false;
        }

        @Override
        public int headerCount() {
            return mResponse.getAllHeaders().length;
        }

        @Override
        public String headerName(int index) {
            Header header = mResponse.getAllHeaders()[index];
            return header == null ? null : header.getName();
        }

        @Override
        public String headerValue(int index) {
            Header header = mResponse.getAllHeaders()[index];
            return header == null ? null : header.getValue();
        }

        @Nullable
        @Override
        public String firstHeaderValue(String name) {
            Header header = mResponse.getFirstHeader(name);
            return header == null ? null : header.getValue();
        }
    }
}
