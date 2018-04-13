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

package org.apache.servicecomb.saga;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.Is.is;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jboss.byteman.agent.submit.Submit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cucumber.api.DataTable;
import cucumber.api.java.After;
import cucumber.api.java8.En;

public class PackStepdefs implements En {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

//  private static final String ALPHA_REST_ADDRESS = "alpha.rest.address";
//  private static final String CAR_SERVICE_ADDRESS = "car.service.address";
//  private static final String HOTEL_SERVICE_ADDRESS = "hotel.service.address";
//  private static final String BOOKING_SERVICE_ADDRESS = "booking.service.address";
//  private static final String INFO_SERVICE_URI = "info.service.uri";
  private static final String ALPHA_REST_ADDRESS = "http://127.0.0.1:8090";
  private static final String CAR_SERVICE_ADDRESS = "http://127.0.0.1:8082";
  private static final String HOTEL_SERVICE_ADDRESS = "http://127.0.0.1:8081";
  private static final String BOOKING_SERVICE_ADDRESS = "http://127.0.0.1:8083";
  private static final String INFO_SERVICE_URI = "/info";
  private static final String[] addresses = {CAR_SERVICE_ADDRESS, HOTEL_SERVICE_ADDRESS};

  private static final Consumer<Map<String, String>[]> NO_OP_CONSUMER = (dataMap) -> {
  };

  private static final Map<String, Submit> submits = new HashMap<>();

  public PackStepdefs() {
    Given("^Car Service is up and running$", () -> {
      probe(CAR_SERVICE_ADDRESS);
    });

    And("^Hotel Service is up and running$", () -> {
      probe(HOTEL_SERVICE_ADDRESS);
    });

    And("^Booking Service is up and running$", () -> {
      probe(BOOKING_SERVICE_ADDRESS);
    });

    And("^Alpha is up and running$", () -> {
      probe(ALPHA_REST_ADDRESS);
    });

    Given("^Install the byteman script ([A-Za-z0-9_\\.]+) to ([A-Za-z]+) Service$", (String script, String service) -> {
      log.info("Install the byteman script {} to {} service", script, service);
      List<String> rules = new ArrayList<>();
//      rules.add("target/test-classes/" + script);
      rules.add("/home/bo/opensource/incubator-servicecomb-saga/acceptance-tests/acceptance-pack/src/test/resources/" + script);

//      String path = "/home/bo/opensource/incubator-servicecomb-saga/acceptance-tests/acceptance-pack/src/test/resources/booking_timeout.btm";
//      File file = new File(path);
//      System.out.println(file.isFile() && file.canRead());



      Submit bm = getBytemanSubmit(service);
      bm.addRulesFromFiles(rules);
    });

    When("^User ([A-Za-z]+) requests to book ([0-9]+) cars and ([0-9]+) rooms$", (username, cars, rooms) -> {
      log.info("Received request from user {} to book {} cars and {} rooms", username, cars, rooms);

      given()
          .pathParam("name", username)
          .pathParam("rooms", rooms)
          .pathParam("cars", cars)
          .when()
          .post("http://127.0.0.1:8083" + "/booking/{name}/{rooms}/{cars}");
    });

    Then("^Alpha records the following events$", (DataTable dataTable) -> {
      Consumer<Map<String, String>[]> columnStrippingConsumer = dataMap -> {
        for (Map<String, String> map : dataMap)
          map.keySet().retainAll(dataTable.topCells());
      };

      dataMatches( "http://127.0.0.1:8090" + "/events", dataTable, columnStrippingConsumer);
    });

    And("^Car Service contains the following booking orders$", (DataTable dataTable) -> {
      dataMatches("http://127.0.0.1:8082"+ "/bookings", dataTable, NO_OP_CONSUMER);
    });

    And("^Hotel Service contains the following booking orders$", (DataTable dataTable) -> {
      dataMatches("http://127.0.0.1:8081" + "/bookings", dataTable, NO_OP_CONSUMER);
    });
  }

  @After
  public void cleanUp() {
    log.info("Cleaning up services");
    for (String address : addresses) {
      given()
          .when()
          .delete(address + "/bookings")
          .then()
          .statusCode(is(200));
    }

    given()
        .when()
        .delete(ALPHA_REST_ADDRESS + "/events")
        .then()
        .statusCode(is(200));

    for (Submit bm : submits.values()) {
      try {
        bm.deleteAllRules();
      } catch (Exception e) {
        log.warn("Fail to delete the byteman rules " + e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void dataMatches(String address, DataTable dataTable, Consumer<Map<String, String>[]> dataProcessor) {
    List<Map<String, String>> expectedMaps = dataTable.asMaps(String.class, String.class);
    List<Map<String, String>> actualMaps = new ArrayList<>();

    await().atMost(2, SECONDS).until(() -> {
      actualMaps.clear();
      Collections.addAll(actualMaps, retrieveDataMaps(address, dataProcessor));

      return expectedMaps.size() == actualMaps.size();
    });

    if (expectedMaps.isEmpty() && actualMaps.isEmpty()) {
      return;
    }

    log.info("Retrieved data {} from service", actualMaps);
    dataTable.diff(DataTable.create(actualMaps));
  }

  @SuppressWarnings("unchecked")
  private Map<String, String>[] retrieveDataMaps(String address, Consumer<Map<String, String>[]> dataProcessor) {
    Map<String, String>[] dataMap = given()
        .when()
        .get(address)
        .then()
        .statusCode(is(200))
        .extract()
        .body()
        .as(Map[].class);

    dataProcessor.accept(dataMap);
    return dataMap;
  }

  private void probe(String address) {
    String infoURI = INFO_SERVICE_URI;
    if (isEmpty(infoURI)) {
      infoURI = "/info";
    }
    log.info("The info service uri is " + infoURI);
    probe(address, infoURI);
  }

  private void probe(String address, String infoURI) {
    log.info("Connecting to service address {}", address);
    given()
        .when()
        .get(address + infoURI)
        .then()
        .statusCode(is(200));
  }

  private Submit getBytemanSubmit(String service) {
    if (submits.containsKey(service)) {
      return submits.get(service);
    } else {
//      String address = System.getProperty("byteman.address");
      String address = "127.0.0.1";
//      String port = System.getProperty(service.toLowerCase() + ".byteman.port");
      String port = "";
      switch (service){
        case "Booking":
          port = "9093";
          break;
        case "Car":
          port = "9091";
          break;
        case "Hotel":
          port = "9092";
          break;
      }
      Submit bm = new Submit(address, Integer.parseInt(port));
      submits.put(service, bm);
      return bm;
    }
  }
}
