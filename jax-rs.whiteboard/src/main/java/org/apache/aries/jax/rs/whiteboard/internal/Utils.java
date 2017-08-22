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

package org.apache.aries.jax.rs.whiteboard.internal;

import org.apache.aries.osgi.functional.Event;
import org.apache.aries.osgi.functional.OSGi;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxrs.lifecycle.ResourceProvider;
import org.apache.cxf.message.Message;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.service.jaxrs.runtime.dto.FailedApplicationDTO;

import javax.ws.rs.core.Application;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.apache.aries.osgi.functional.OSGi.bundleContext;
import static org.apache.aries.osgi.functional.OSGi.just;
import static org.apache.aries.osgi.functional.OSGi.nothing;
import static org.apache.aries.osgi.functional.OSGi.onClose;
import static org.apache.aries.osgi.functional.OSGi.register;

/**
 * @author Carlos Sierra Andrés
 */
public class Utils {

    public static Map<String, Object> getProperties(ServiceReference<?> sref) {
        String[] propertyKeys = sref.getPropertyKeys();
        Map<String, Object> properties = new HashMap<>(propertyKeys.length);

        for (String key : propertyKeys) {
            properties.put(key, sref.getProperty(key));
        }

        return properties;
    }

    public static <T> OSGi<T> service(ServiceReference<T> serviceReference) {
        return
            bundleContext().flatMap(bundleContext ->
            onClose(() -> bundleContext.ungetService(serviceReference)).then(
            just(bundleContext.getService(serviceReference))
        ));
    }

    public static <T> OSGi<ServiceObjects<T>> serviceObjects(
        ServiceReference<T> serviceReference) {

        return
            bundleContext().flatMap(bundleContext ->
            just(bundleContext.getServiceObjects(serviceReference))
        );
    }

    public static OSGi<?> deployRegistrator(
        Bus bus, Application application, Map<String, Object> props) {

        try {
            CXFJaxRsServiceRegistrator registrator =
                new CXFJaxRsServiceRegistrator(bus, application);

            return
                onClose(registrator::close).then(
                register(CXFJaxRsServiceRegistrator.class, registrator, props)
            );
        }
        catch (Exception e) {
            return register(
                FailedApplicationDTO.class, new FailedApplicationDTO(), props);
        }
    }

    public static OSGi<?> safeRegisterGeneric(
        ServiceReference<?> serviceReference,
        String applicationName,
        CXFJaxRsServiceRegistrator registrator,
        AriesJaxRSServiceRuntime runtime) {

        if (isExtension(serviceReference)) {
            return safeRegisterExtension(
                serviceReference, applicationName, registrator, runtime);
        }
        else {
            return safeRegisterEndpoint(
                serviceReference, applicationName, registrator, runtime);
        }
    }

    private static boolean isExtension(ServiceReference<?> serviceReference) {
        Object extensionProperty = serviceReference.getProperty(
            "osgi.jaxrs.extension");

        return
            (extensionProperty != null) &&
            (extensionProperty instanceof Boolean) &&
            ((boolean) extensionProperty);
    }

    public static OSGi<?> safeRegisterExtension(
        ServiceReference<?> serviceReference,
        String applicationName, CXFJaxRsServiceRegistrator registrator,
        AriesJaxRSServiceRuntime runtime) {

        return
            onlyGettables(
                just(serviceReference),
                runtime::addNotGettableExtension,
                runtime::removeNotGettableExtension
            ).foreach(
                registrator::addProvider,
                registrator::removeProvider
            ).foreach(
                __ -> runtime.addApplicationExtension(
                    applicationName, serviceReference),
                __ -> runtime.removeApplicationExtension(
                    applicationName, serviceReference)
            );
    }

    public static <T> OSGi<?> safeRegisterEndpoint(
        ServiceReference<T> serviceReference,
        String applicationName,
        CXFJaxRsServiceRegistrator registrator,
        AriesJaxRSServiceRuntime runtime) {

        return
            onlyGettables(
                just(serviceReference),
                runtime::addNotGettableEndpoint,
                runtime::removeNotGettableEndpoint
            ).flatMap(
                tuple -> serviceObjects(serviceReference).flatMap(
                    serviceObjects -> registerEndpoint(
                        registrator, serviceObjects).flatMap(
                            resourceProvider ->
                                onClose(
                                    () -> unregisterEndpoint(
                                        registrator, resourceProvider)
                                )
                    )
                )
            ).foreach(
                __ -> runtime.addApplicationEndpoint(
                    applicationName, serviceReference),
                __ -> runtime.removeApplicationEndpoint(
                    applicationName, serviceReference)
            );
    }

    public static <T extends Comparable<? super T>> OSGi<T> repeatInOrder(
        OSGi<T> program) {

        return program.route(new RepeatInOrderRouter<>());
    }

    public static <T> OSGi<ServiceTuple<T>> onlyGettables(
        OSGi<ServiceReference<T>> program,
        Consumer<ServiceReference<T>> whenAddedNotGettable,
        Consumer<ServiceReference<T>> whenLeavingNotGettable) {

        return bundleContext().flatMap(bundleContext ->
            program.flatMap(serviceReference -> {
                T service = null;

                try {
                    service = bundleContext.getService(serviceReference);
                }
                catch (Exception e){
                }
                if (service == null) {
                    whenAddedNotGettable.accept(serviceReference);

                    return
                        onClose(
                            () -> whenLeavingNotGettable.accept(
                                serviceReference)
                        ).then(
                            nothing()
                        );
                }
                return
                    onClose(
                        () -> bundleContext.ungetService(serviceReference)
                    ).then(
                        just(new ServiceTuple<>(serviceReference, service))
                    );
            }
        ));
    }

    public static <T> OSGi<ResourceProvider>
        registerEndpoint(
            CXFJaxRsServiceRegistrator registrator,
            ServiceObjects<T> serviceObjects) {

        ResourceProvider resourceProvider = getResourceProvider(serviceObjects);
        registrator.add(resourceProvider);
        return just(resourceProvider);
    }

    public static String safeToString(Object object) {
        return object == null ? "" : object.toString();
    }

    public static <T> ResourceProvider getResourceProvider(
        ServiceObjects<T> serviceObjects) {

        ServiceReference<T> serviceReference = serviceObjects.getServiceReference();

        return new ComparableResourceProvider(serviceReference, serviceObjects);
    }

    public static void unregisterEndpoint(
        CXFJaxRsServiceRegistrator registrator,
        ResourceProvider resourceProvider) {

        registrator.remove(resourceProvider);
    }

    private static class RepeatInOrderRouter<T extends Comparable<? super T>>
        implements Consumer<OSGi.Router<T>> {

        private final TreeSet<Event<T>> _treeSet;

        public RepeatInOrderRouter() {
            Comparator<Event<T>> comparing = Comparator.comparing(
                Event::getContent);

            _treeSet = new TreeSet<>(comparing.reversed());
        }

        @Override
        public void accept(OSGi.Router<T> router) {
            router.onIncoming(ev -> {
                _treeSet.add(ev);

                SortedSet<Event<T>> events = _treeSet.tailSet(ev, false);
                events.forEach(router::signalLeave);

                router.signalAdd(ev);

                events.forEach(router::signalAdd);
            });
            router.onLeaving(ev -> {
                _treeSet.remove(ev);

                SortedSet<Event<T>> events = _treeSet.tailSet(ev, false);
                events.forEach(router::signalLeave);

                router.signalLeave(ev);

                events.forEach(router::signalAdd);
            });
            router.onClose(() -> {
                _treeSet.forEach(router::signalLeave);

                _treeSet.clear();
            });
        }

    }

    public static <K, T extends Comparable<? super T>> OSGi<T> highestPer(
        Function<T, K> keySupplier, OSGi<T> program,
        Consumer<T> onAddingShadowed, Consumer<T> onRemovedShadowed) {

        ConcurrentHashMap<K, TreeSet<Event<T>>> map = new ConcurrentHashMap<>();

        return program.route(
            router -> {
                router.onIncoming(e -> {
                    K key = keySupplier.apply(e.getContent());

                    Comparator<Event<T>> comparator = Comparator.comparing(
                        Event::getContent);

                    NavigableSet<Event<T>> set = map.computeIfAbsent(
                        key, __ -> new TreeSet<>(comparator));

                    Event<T> last = set.size() > 0 ? set.last() : null;

                    boolean higher =
                        (last == null) || (comparator.compare(e, last) > 0);

                    if (higher) {
                        if (last != null) {
                            router.signalLeave(last);

                            onAddingShadowed.accept(last.getContent());
                        }

                        router.signalAdd(e);
                    }
                    else {
                        onAddingShadowed.accept(e.getContent());
                    }

                    set.add(e);
                });
                router.onLeaving(e -> {
                    T content = e.getContent();

                    K key = keySupplier.apply(content);

                    TreeSet<Event<T>> set = map.get(key);

                    Event<T> last = set.last();

                    if (content.equals(last.getContent())) {
                        router.signalLeave(e);

                        Event<T> penultimate = set.lower(last);

                        if (penultimate != null) {
                            router.signalAdd(penultimate);

                            onRemovedShadowed.accept(penultimate.getContent());
                        }
                    }
                    else {
                        onRemovedShadowed.accept(content);
                    }

                    set.removeIf(t -> t.getContent().equals(content));
                });
            }
        );
    }

    public static class ComparableResourceProvider
        implements ResourceProvider, Comparable<ComparableResourceProvider> {

        private ServiceReference<?> _serviceReference;
        private final ServiceObjects<?> _serviceObjects;

        public ComparableResourceProvider(
            ServiceReference<?> serviceReference,
            ServiceObjects<?> serviceObjects) {
            _serviceReference = serviceReference;

            _serviceObjects = serviceObjects;
        }

        @Override
        public int compareTo(ComparableResourceProvider o) {
            return _serviceReference.compareTo(o._serviceReference);
        }

        @Override
        public Object getInstance(Message m) {
            return _serviceObjects.getService();
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void releaseInstance(Message m, Object o) {
            ((ServiceObjects)_serviceObjects).ungetService(o);
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        @Override
        public Class<?> getResourceClass() {
            Object service = _serviceObjects.getService();

            try {
                return service.getClass();
            }
            finally {
                ((ServiceObjects)_serviceObjects).ungetService(service);
            }
        }

        @Override
        public boolean isSingleton() {
            return false;
        }

    }

    public static class ServiceTuple<T> implements Comparable<ServiceTuple<T>> {

        private final ServiceReference<T> _serviceReference;
        private final T _service;

        public ServiceTuple(ServiceReference<T> a, T service) {
            _serviceReference = a;
            _service = service;
        }

        @Override
        public int compareTo(ServiceTuple<T> o) {
            return _serviceReference.compareTo(o._serviceReference);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ServiceTuple<?> that = (ServiceTuple<?>) o;

            return _serviceReference.equals(that._serviceReference);
        }

        @Override
        public int hashCode() {
            return _serviceReference.hashCode();
        }

        public ServiceReference<T> getServiceReference() {
            return _serviceReference;
        }

        public T getService() {
            return _service;
        }
    }

}
