/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.utils;

import org.apache.flink.kubernetes.operator.exception.DeploymentFailedException;
import org.apache.flink.kubernetes.operator.observer.JobStatusObserver;

import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** Test for {@link EventUtils}. */
@EnableKubeAPIServer
public class EventUtilsApiServerTest {

    static KubernetesClient client;

    @Test
    public void testVolumeMountErrors() {
        var pod =
                new PodBuilder()
                        .withNewMetadata()
                        .withName("test")
                        .withNamespace("default")
                        .endMetadata()
                        .withNewStatus()
                        .endStatus()
                        .build();

        // No conditions, no error expected
        EventUtils.checkForVolumeMountErrors(client, pod);

        var conditions = new ArrayList<PodCondition>();
        pod.getStatus().setConditions(conditions);

        // No conditions, no error expected
        EventUtils.checkForVolumeMountErrors(client, pod);

        // Create error events
        createPodEvent("e1", "reason1", "msg1", pod);
        createPodEvent("e2", "FailedMount", "mountErr", pod);

        var conditionMap = new HashMap<String, String>();

        // Pod initialized completely, shouldn't check events
        conditionMap.put("Initialized", "True");
        conditionMap.put("Ready", "False");

        conditions.clear();
        conditionMap.forEach(
                (t, s) ->
                        conditions.add(
                                new PodConditionBuilder().withType(t).withStatus(s).build()));
        EventUtils.checkForVolumeMountErrors(client, pod);

        // Pod initialized completely, shouldn't check events
        conditionMap.put("PodReadyToStartContainers", "True");
        conditionMap.put("Initialized", "False");

        conditions.clear();
        conditionMap.forEach(
                (t, s) ->
                        conditions.add(
                                new PodConditionBuilder().withType(t).withStatus(s).build()));
        EventUtils.checkForVolumeMountErrors(client, pod);

        // Check event only when not ready to start
        conditionMap.put("PodReadyToStartContainers", "False");
        conditions.clear();
        conditionMap.forEach(
                (t, s) ->
                        conditions.add(
                                new PodConditionBuilder().withType(t).withStatus(s).build()));

        try {
            EventUtils.checkForVolumeMountErrors(client, pod);
            fail("Exception not thrown");
        } catch (DeploymentFailedException dfe) {
            assertEquals("FailedMount", dfe.getReason());
            assertEquals("mountErr", dfe.getMessage());
        }

        // Old kubernetes without PodReadyToStartContainers
        conditionMap.remove("PodReadyToStartContainers");
        conditionMap.put("Initialized", "False");
        conditions.clear();
        conditionMap.forEach(
                (t, s) ->
                        conditions.add(
                                new PodConditionBuilder().withType(t).withStatus(s).build()));

        try {
            EventUtils.checkForVolumeMountErrors(client, pod);
            fail("Exception not thrown");
        } catch (DeploymentFailedException dfe) {
            assertEquals("FailedMount", dfe.getReason());
            assertEquals("mountErr", dfe.getMessage());
        }
    }

    private void createPodEvent(String name, String reason, String msg, Pod pod) {
        var event =
                new EventBuilder()
                        .withApiVersion("v1")
                        .withInvolvedObject(EventUtils.getObjectReference(pod))
                        .withType("type")
                        .withReason(reason)
                        .withFirstTimestamp(Instant.now().toString())
                        .withLastTimestamp(Instant.now().toString())
                        .withNewSource()
                        .withComponent("pod")
                        .endSource()
                        .withCount(1)
                        .withMessage(msg)
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(pod.getMetadata().getNamespace())
                        .endMetadata()
                        .build();
        client.resource(event).create();
    }

    @Test
    public void testFindingLatestJobExceptionTs() throws Exception {
        // Testing with pods instead of FlinkDeployments due to the local Kube server limitations
        var pod =
                new PodBuilder()
                        .withNewMetadata()
                        .withName("test2")
                        .withNamespace("default")
                        .endMetadata()
                        .withNewStatus()
                        .endStatus()
                        .build();

        assertEquals(Optional.empty(), EventUtils.findLastJobExceptionTsFromK8s(client, pod));

        var now = Instant.now();
        createExceptionEvent(pod, "ex1", now);
        assertEquals(Optional.of(now), EventUtils.findLastJobExceptionTsFromK8s(client, pod));

        createExceptionEvent(pod, "ex2", now.plus(Duration.ofSeconds(1)));
        createExceptionEvent(pod, "ex3", now.minus(Duration.ofSeconds(1)));

        assertEquals(
                Optional.of(now.plus(Duration.ofSeconds(1))),
                EventUtils.findLastJobExceptionTsFromK8s(client, pod));
    }

    private static void createExceptionEvent(HasMetadata resource, String name, Instant now) {
        var event =
                new EventBuilder()
                        .withApiVersion("v1")
                        .withInvolvedObject(EventUtils.getObjectReference(resource))
                        .withType("type")
                        .withReason(EventRecorder.Reason.JobException.name())
                        .withFirstTimestamp(Instant.now().toString())
                        .withLastTimestamp(Instant.now().toString())
                        .withNewSource()
                        .withComponent(EventRecorder.Component.Job.name())
                        .endSource()
                        .withCount(1)
                        .withMessage("m")
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(resource.getMetadata().getNamespace())
                        .addToAnnotations(JobStatusObserver.EXCEPTION_TIMESTAMP, now.toString())
                        .endMetadata()
                        .build();
        client.resource(event).create();
    }
}
