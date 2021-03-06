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

import static org.jboss.arquillian.protocol.servlet.ServletUtil.WEB_XML_PATH;

import java.util.Collection;
import java.util.Map;

import org.jboss.arquillian.ce.utils.Archives;
import org.jboss.arquillian.container.test.spi.TestDeployment;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentPackager;
import org.jboss.arquillian.container.test.spi.client.deployment.ProtocolArchiveProcessor;
import org.jboss.arquillian.protocol.servlet.Processor;
import org.jboss.arquillian.protocol.servlet.ServletMethodExecutor;
import org.jboss.arquillian.protocol.servlet.arq514hack.descriptors.api.web.WebAppDescriptor;
import org.jboss.arquillian.protocol.servlet.v_2_5.ProtocolDeploymentAppender;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Filters;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptors;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class CEProtocolDeploymentPackager implements DeploymentPackager {

    public Archive<?> generateDeployment(TestDeployment testDeployment, Collection<ProtocolArchiveProcessor> processors) {
        Archive<?> archive = testDeployment.getApplicationArchive();

        WebArchive protocol = new ProtocolDeploymentAppender().createAuxiliaryArchive();
        Collection<Archive<?>> auxiliaryArchives = testDeployment.getAuxiliaryArchives();
        Processor processor = new Processor(testDeployment, processors);

        if (archive instanceof EnterpriseArchive) {
            final EnterpriseArchive ear = (EnterpriseArchive) archive;

            Map<ArchivePath, Node> wars = ear.getContent(Filters.include(".*\\.war"));
            for (Map.Entry<ArchivePath, Node> warEntry : wars.entrySet()) {
                WebArchive war = ear.getAsType(WebArchive.class, warEntry.getKey());
                handleWar(war, protocol, processor);
            }

            addLibrariesToEar(ear, auxiliaryArchives);
        } else if (archive instanceof WebArchive) {
            final WebArchive war = (WebArchive) archive;
            handleWar(war, protocol, processor, auxiliaryArchives);
        } else if (archive instanceof JavaArchive) {
            final WebArchive war = Archives.generateDummyWebArchive().addAsLibraries(archive); // add jar as lib
            handleWar(war, protocol, processor, auxiliaryArchives);
        } else {
            throw new IllegalArgumentException("Cannot handle the type of deployment: " + archive);
        }

        for (Archive<?> lib : auxiliaryArchives) {
            if (Archives.isMgmtClientJar(lib)) {
                Archives.handleDependencies(archive);
                break;
            }
        }

        return archive;
    }

    private Archive<?> handleWar(WebArchive war, WebArchive protocol, Processor processor, Collection<Archive<?>> auxiliaryArchives) {
        handleWar(war, protocol, processor);
        war.addAsLibraries(auxiliaryArchives);
        return war;
    }

    protected void addLibrariesToEar(EnterpriseArchive ear, Collection<Archive<?>> libs) {
        ear.addAsLibraries(libs);
    }

    private Archive<?> handleWar(WebArchive war, WebArchive protocol, Processor processor) {
        if (war.contains(WEB_XML_PATH)) {
            WebAppDescriptor applicationWebXml = Descriptors.importAs(WebAppDescriptor.class).fromStream(war.get(WEB_XML_PATH).getAsset().openStream());
            // SHRINKWRAP-187, to eager on not allowing overrides, delete it first
            war.delete(WEB_XML_PATH);
            war.setWebXML(new StringAsset(mergeWithArqDescriptor(applicationWebXml).exportAsString()));
            war.merge(protocol, Filters.exclude(".*web\\.xml.*"));
        } else {
            war.merge(protocol);
        }

        processor.process(war);

        return war;
    }

    static WebAppDescriptor mergeWithArqDescriptor(WebAppDescriptor descriptor) {
        // use String v. of desc.servlet(..) so we don't force Servlet API on classpath
        descriptor.servlet(
            ServletMethodExecutor.ARQUILLIAN_SERVLET_NAME,
            "org.jboss.arquillian.protocol.servlet.runner.ServletTestRunner",
            new String[]{ServletMethodExecutor.ARQUILLIAN_SERVLET_MAPPING});

        return descriptor;
    }
}