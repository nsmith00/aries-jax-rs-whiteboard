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

package test.types;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

@Path("whiteboard/async")
public class TestAsyncResource {

    private final Runnable	preResume;
    private final Runnable postResume;

    public TestAsyncResource(Runnable preResume, Runnable postResume) {
        this.preResume = preResume;
        this.postResume = postResume;
    }

    @GET
    @Path("{name}")
    @Produces(MediaType.TEXT_PLAIN)
    public void echo(@Suspended AsyncResponse async,
                     @PathParam("name") String value) {

        new Thread(() -> {
            try {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    preResume.run();
                    async.resume(e);
                    return;
                }
                preResume.run();
                async.resume(value);
            } finally {
                postResume.run();
            }
        }).start();
    }
}
