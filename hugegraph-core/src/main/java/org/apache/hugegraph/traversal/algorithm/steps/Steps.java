/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements. See the NOTICE file distributed with this
 *  * work for additional information regarding copyright ownership. The ASF
 *  * licenses this file to You under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  * License for the specific language governing permissions and limitations
 *  * under the License.
 *
 */

package org.apache.hugegraph.traversal.algorithm.steps;

import static org.apache.hugegraph.traversal.algorithm.HugeTraverser.NO_LIMIT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.schema.EdgeLabel;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.traversal.algorithm.HugeTraverser;
import org.apache.hugegraph.traversal.optimize.TraversalUtil;
import org.apache.hugegraph.type.define.Directions;
import org.apache.hugegraph.util.E;

public class Steps {

    protected final Map<Id, StepEntity> edgeSteps;
    protected final Map<Id, StepEntity> vertexSteps;
    protected final Directions direction;
    protected final long degree;
    protected final long skipDegree;


    public Steps(HugeGraph graph, Directions direction,
                 Map<String, Map<String, Object>> vSteps,
                 Map<String, Map<String, Object>> eSteps,
                 long degree, long skipDegree) {
        E.checkArgument(degree == NO_LIMIT || degree > 0L,
                        "The max degree must be > 0 or == -1, but got: %s", degree);
        HugeTraverser.checkSkipDegree(skipDegree, degree, NO_LIMIT);

        this.direction = direction;

        // parse vertex steps
        this.vertexSteps = new HashMap<>();
        if (vSteps != null && !vSteps.isEmpty()) {
            initVertexFilter(graph, vSteps);
        }

        // parse edge steps
        this.edgeSteps = new HashMap<>();
        if (eSteps != null && !eSteps.isEmpty()) {
            initEdgeFilter(graph, eSteps);
        }

        this.degree = degree;
        this.skipDegree = skipDegree;
    }

    private void initVertexFilter(HugeGraph graph, Map<String, Map<String, Object>> vSteps) {
        for (Map.Entry<String, Map<String, Object>> entry : vSteps.entrySet()) {
            if (checkEntryEmpty(entry)) {
                continue;
            }
            E.checkArgument(entry.getKey() != null && !entry.getKey().isEmpty(),
                            "The vertex step label could not be null");

            VertexLabel vertexLabel = graph.vertexLabel(entry.getKey());
            StepEntity stepEntity = handleStepEntity(graph, entry, vertexLabel.id());
            this.vertexSteps.put(vertexLabel.id(), stepEntity);
        }
    }

    private void initEdgeFilter(HugeGraph graph, Map<String, Map<String, Object>> eSteps) {
        for (Map.Entry<String, Map<String, Object>> entry : eSteps.entrySet()) {
            if (checkEntryEmpty(entry)) {
                continue;
            }
            E.checkArgument(entry.getKey() != null && !entry.getKey().isEmpty(),
                            "The edge step label could not be null");

            EdgeLabel edgeLabel = graph.edgeLabel(entry.getKey());
            StepEntity stepEntity = handleStepEntity(graph, entry, edgeLabel.id());
            this.edgeSteps.put(edgeLabel.id(), stepEntity);
        }
    }

    private StepEntity handleStepEntity(HugeGraph graph,
                                        Map.Entry<String, Map<String, Object>> entry,
                                        Id id) {
        Map<Id, Object> properties = null;
        if (entry.getValue() != null) {
            properties = TraversalUtil.transProperties(graph, entry.getValue());
        }
        return new StepEntity(id, entry.getKey(), properties);
    }

    private boolean checkEntryEmpty(Map.Entry<String, Map<String, Object>> entry) {
        return (entry.getKey() == null || entry.getKey().isEmpty()) &&
               (entry.getValue() == null || entry.getValue().isEmpty());
    }

    public long degree() {
        return degree;
    }

    public Map<Id, StepEntity> edgeSteps() {
        return edgeSteps;
    }

    public Map<Id, StepEntity> vertexSteps() {
        return vertexSteps;
    }

    public long skipDegree() {
        return skipDegree;
    }

    public Directions direction() {
        return direction;
    }

    public long limit() {
        return this.skipDegree > 0L ? this.skipDegree : this.degree;
    }

    public List<Id> edgeLabels() {
        return new ArrayList<>(this.edgeSteps.keySet());
    }

    public boolean isVertexEmpty() {
        return this.vertexSteps.isEmpty();
    }

    public boolean isEdgeStepPropertiesEmpty() {
        if (this.edgeSteps.isEmpty()) {
            return true;
        }
        for (Map.Entry<Id, StepEntity> entry : this.edgeSteps.entrySet()) {
            Map<Id, Object> properties = entry.getValue().getProperties();
            if (properties != null && !properties.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Steps{" +
               "edgeSteps=" + edgeSteps +
               ", vertexSteps=" + vertexSteps +
               ", direction=" + direction +
               ", degree=" + degree +
               ", skipDegree=" + skipDegree +
               '}';
    }

    public static class StepEntity {
        protected final Id id;
        protected final String label;
        protected final Map<Id, Object> properties;

        public StepEntity(Id id, String label, Map<Id, Object> properties) {
            this.id = id;
            this.label = label;
            this.properties = properties;
        }

        public Id getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public Map<Id, Object> getProperties() {
            return properties;
        }

        @Override
        public String toString() {
            return String.format("StepEntity{id=%s,label=%s," +
                                 "properties=%s}", this.id,
                                 this.label, this.properties);
        }
    }
}