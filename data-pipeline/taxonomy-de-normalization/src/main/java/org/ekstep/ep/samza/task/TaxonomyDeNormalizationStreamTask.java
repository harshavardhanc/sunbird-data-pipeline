/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ekstep.ep.samza.task;

import com.google.gson.Gson;
import com.library.checksum.system.ChecksumGenerator;
import com.library.checksum.system.KeysToAccept;
import org.apache.samza.config.Config;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.*;
import org.ekstep.ep.samza.service.TaxonomyService;
import org.ekstep.ep.samza.system.TaxonomyCache;
import org.ekstep.ep.samza.system.TaxonomyEvent;

import java.io.PrintStream;
import java.util.Map;

public class
        TaxonomyDeNormalizationStreamTask implements StreamTask, InitableTask {

    private String successTopic;
    private String failedTopic;

    private TaxonomyCache taxonomyCache;
    private KeyValueStore<String, Object> taxonomyStore;
    private TaxonomyService taxonomyService;
    private TaxonomyEvent taxonomyEvent;
    private String apiHost;
    private final String apiUrl = "/taxonomy-service/taxonomy/hierarchy/literacy_v2?cfields=description,name";
    private ChecksumGenerator checksumGenerator;
    private String[] keys_to_accept;

    @Override
    public void init(Config config, TaskContext context) {

        successTopic = config.get("output.success.topic.name", "events.ecosystem");
        failedTopic = config.get("output.failed.topic.name", "failed_taxonomy_events");

        apiHost = config.get("api.host");
        taxonomyService = new TaxonomyService(apiHost,apiUrl);
        taxonomyStore = (KeyValueStore<String, Object>) context.getStore("taxonomy-de-normalization");
        taxonomyCache = new TaxonomyCache(taxonomyStore);
        taxonomyCache.setTTL(1 * 60 * 60 * 1000L);
        taxonomyCache.setService(taxonomyService);

        String[] keys_to_accept = {"uid", "ts", "cid", "gdata","edata"};
        checksumGenerator = new ChecksumGenerator(new KeysToAccept(keys_to_accept));
    }

    @Override
    public void process(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator){
        Map<String, Object> jsonObject;
        TaxonomyEvent taxonomyEvent = new TaxonomyEvent();
        try {
            jsonObject = (Map<String, Object>) envelope.getMessage();
            taxonomyEvent = new TaxonomyEvent(new Gson().toJson(jsonObject));
            processEvent(taxonomyEvent, collector);
        }
        catch (Exception e) {
            System.err.println("Exception: " + e);
            e.printStackTrace(new PrintStream(System.err));
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", failedTopic), taxonomyEvent.getMap()));
        }
    }

    public void processEvent(TaxonomyEvent taxonomyEvent,MessageCollector collector){
        // TODO make cache a Class Level attribute
        try{
            checksumGenerator.stampChecksum(taxonomyEvent);
            taxonomyEvent.setCache(taxonomyCache);
            taxonomyEvent.denormalize();
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", successTopic), taxonomyEvent.getMap()));
        } catch (java.io.IOException e) {
            System.err.println("Communication Error: " + e);
            e.printStackTrace(new PrintStream(System.err));
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", failedTopic), taxonomyEvent.getMap()));
        }
    }
}