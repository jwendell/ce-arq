/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.arquillian.ce.protocol;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Timer;
import java.util.logging.Logger;

import org.jboss.arquillian.ce.proxy.Proxy;
import org.jboss.arquillian.ce.runinpod.RunInPodUtils;
import org.jboss.arquillian.ce.utils.Archives;
import org.jboss.arquillian.ce.utils.DeploymentContext;
import org.jboss.arquillian.ce.utils.Strings;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.container.test.spi.command.CommandCallback;
import org.jboss.arquillian.protocol.servlet.ServletMethodExecutor;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;
import org.jboss.shrinkwrap.api.Archive;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class CEServletExecutor extends ServletMethodExecutor {
    private static final Logger log = Logger.getLogger(CEServletExecutor.class.getName());

    private String contextRoot;
    private Map<String, String> labels;
    private Proxy proxy;

    public CEServletExecutor(CEProtocolConfiguration configuration, ProtocolMetaData protocolMetaData, CommandCallback callback) {
        this.config = configuration;
        this.callback = callback;

        this.contextRoot = readContextRoot(protocolMetaData);
        DeploymentContext deploymentContext = DeploymentContext.getDeploymentContext(protocolMetaData);

        this.labels = deploymentContext.getLabels();
        this.proxy = deploymentContext.getProxy();
    }

    static String readContextRoot(ProtocolMetaData protocolMetaData) {
        Collection<HTTPContext> contexts = protocolMetaData.getContexts(HTTPContext.class);
        for (HTTPContext context : contexts) {
            Servlet arqServlet = context.getServletByName(ARQUILLIAN_SERVLET_NAME);
            if (arqServlet != null) {
                return arqServlet.getContextRoot();
            }
        }
        throw new IllegalArgumentException("No Arquillian servlet in HTTPContext meta data!");
    }

    public TestResult invoke(final TestMethodExecutor testMethodExecutor) {
        if (testMethodExecutor == null) {
            throw new IllegalArgumentException("TestMethodExecutor must be specified");
        }

        Class<?> testClass = testMethodExecutor.getInstance().getClass();

        String context;
        String podName;
        if (isRunInPod(testMethodExecutor)) {
            context = "/runinpod";
            podName = findRunInPod();
        } else {
            context = contextRoot;
            podName = locatePodName(testMethodExecutor);
        }

        String url = proxy.url(podName, 8080, context + ARQUILLIAN_SERVLET_MAPPING, "outputMode=serializedObject&className=" + testClass.getName() + "&methodName=" + testMethodExecutor.getMethod().getName());
        log.info(String.format("Invoking test, url: %s", url));
        String eventUrl = proxy.url(podName, 8080, context + ARQUILLIAN_SERVLET_MAPPING, "outputMode=serializedObject&className=" + testClass.getName() + "&methodName=" + testMethodExecutor.getMethod().getName() + "&cmd=event");

        Timer eventTimer = null;
        try {
            eventTimer = createCommandServicePullTimer(eventUrl);
            return executeWithRetry(url, TestResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Error launching test " + testClass.getName() + " " + testMethodExecutor.getMethod(), e);
        } finally {
            if (eventTimer != null) {
                eventTimer.cancel();
            }
        }
    }

    protected <T> T execute(String url, Class<T> returnType, Object requestObject) throws Exception {
        return proxy.post(url, returnType, requestObject);
    }

    private boolean isRunInPod(TestMethodExecutor testMethodExecutor) {
        return RunInPodUtils.isRunInPod(testMethodExecutor.getInstance().getClass(), testMethodExecutor.getMethod());
    }

    private String findRunInPod() {
        Archive<?> dummy = Archives.generateDummyWebArchive("runinpod.war");
        Map<String, String> labels = DeploymentContext.getDeploymentLabels(dummy);
        return proxy.findPod(labels, 0);
    }

    private String locatePodName(TestMethodExecutor testMethodExecutor) {
        Method method = testMethodExecutor.getMethod();
        TargetsContainer tc = method.getAnnotation(TargetsContainer.class);
        int index = 0;
        if (tc != null) {
            String value = tc.value();
            index = Strings.parseNumber(value);
        }

        OperateOnDeployment ood = method.getAnnotation(OperateOnDeployment.class);
        if (ood != null) {
            return findDeploymentsPod(labels);
        }

        return proxy.findPod(labels, index);
    }

    private String findDeploymentsPod(Map<String, String> labels) {
        return proxy.findPod(labels, 0);
    }

}
