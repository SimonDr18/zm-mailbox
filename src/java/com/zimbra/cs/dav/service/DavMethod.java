/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2006, 2007, 2009, 2010, 2011, 2013 Zimbra Software, LLC.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.4 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.dav.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.dom4j.io.XMLWriter;
import org.mortbay.io.EndPoint;
import org.mortbay.io.nio.SelectChannelEndPoint;
import org.mortbay.jetty.HttpConnection;

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.dav.DavContext;
import com.zimbra.cs.dav.DavException;
import com.zimbra.cs.dav.DavProtocol;

/**
 * Base class for DAV methods.
 *
 * @author jylee
 *
 */
public abstract class DavMethod {
    public abstract String getName();
    public abstract void handle(DavContext ctxt) throws DavException, IOException, ServiceException;

    public void checkPrecondition(DavContext ctxt) throws DavException {
    }

    public void checkPostcondition(DavContext ctxt) throws DavException {
    }

    @Override
    public String toString() {
        return "DAV method " + getName();
    }

    public String getMethodName() {
        return getName();
    }

    protected static final int STATUS_OK = HttpServletResponse.SC_OK;

    protected void sendResponse(DavContext ctxt) throws IOException {
        if (ctxt.isResponseSent())
            return;
        HttpServletResponse resp = ctxt.getResponse();
        resp.setStatus(ctxt.getStatus());
        String compliance = ctxt.getDavCompliance();
        if (compliance != null)
            setResponseHeader(resp, DavProtocol.HEADER_DAV, compliance);
        if (ctxt.hasResponseMessage()) {
            resp.setContentType(DavProtocol.DAV_CONTENT_TYPE);
            DavResponse respMsg = ctxt.getDavResponse();
            respMsg.writeTo(resp.getOutputStream());
        }
        ctxt.responseSent();
    }

    public static void setResponseHeader(HttpServletResponse resp, String name, String value) {
        while (value != null) {
            String val = value;
            if (value.length() > 70) {
                int index = value.lastIndexOf(',', 70);
                if (index == -1) {
                    ZimbraLog.dav.warn("header value is too long for %s : %s", name, value);
                    return;
                }
                val = value.substring(0, index);
                value = value.substring(index+1).trim();
            } else {
                value = null;
            }
            resp.addHeader(name, val);
        }
    }

    public HttpMethod toHttpMethod(DavContext ctxt, String targetUrl) throws IOException, DavException {
        if (ctxt.getUpload() != null && ctxt.getUpload().getSize() > 0) {
            PostMethod method = new PostMethod(targetUrl) {
                @Override
                public String getName() { return getMethodName(); }
            };
            RequestEntity reqEntry;
            if (ctxt.hasRequestMessage()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                XMLWriter writer = new XMLWriter(baos);
                writer.write(ctxt.getRequestMessage());
                reqEntry = new ByteArrayRequestEntity(baos.toByteArray());
            } else { // this could be a huge upload
                reqEntry = new InputStreamRequestEntity(ctxt.getUpload().getInputStream(), ctxt.getUpload().getSize());
            }
            method.setRequestEntity(reqEntry);
            return method;
        }
        return new GetMethod(targetUrl) {
            @Override
            public String getName() { return getMethodName(); }
        };
    }

    /**
     * Implemented for bug 79865
     *
     * Disable the Jetty timeout for for this request.
     *
     * By default (and our normal configuration) Jetty has a 30 second idle timeout (10 if the server is busy) for
     * connection endpoints. There's another task that keeps track of what connections have timeouts and periodically
     * works over a queue and closes endpoints that have been timed out. This plays havoc with DAV over slow connections
     * and whenever we have a long pause.
     *
     * @throws IOException
     */
    protected void disableJettyTimeout() throws IOException {
        if (LC.zimbra_dav_disable_timeout.booleanValue()) {
            EndPoint endPoint = HttpConnection.getCurrentConnection().getEndPoint();
            if (endPoint instanceof SelectChannelEndPoint) {
                SelectChannelEndPoint scEndPoint = (SelectChannelEndPoint) endPoint;
                scEndPoint.setIdleExpireEnabled(false);
            }
        }
    }

}
