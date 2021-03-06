/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.aries.jax.rs.whiteboard.internal.client;

import javax.ws.rs.client.ClientBuilder;

import org.apache.cxf.jaxrs.client.PromiseRxInvokerProviderImpl;
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceRegistration;

public class ClientBuilderFactory
    implements PrototypeServiceFactory<ClientBuilder> {

    @Override
    public ClientBuilder getService(
        Bundle bundle, ServiceRegistration<ClientBuilder> registration) {

        ClientBuilderImpl clientBuilder = new ClientBuilderImpl();

        return clientBuilder.register(new PromiseRxInvokerProviderImpl());
    }

    @Override
    public void ungetService(
        Bundle bundle, ServiceRegistration<ClientBuilder> registration,
        ClientBuilder service) {

    }

}