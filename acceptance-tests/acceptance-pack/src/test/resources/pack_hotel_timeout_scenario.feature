# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

Feature: Alpha records transaction events

  Scenario: Hotel transaction timeout and will be compensated
    Given Car Service is up and running
    And Hotel Service is up and running
    And Booking Service is up and running
    And Alpha is up and running

    Given Install the byteman script hotel_timeout.btm to Hotel Service

    When User Jason requests to book 1 cars and 1 rooms

    Then Alpha records the following events
      | serviceName  | type               |
      | pack-booking | SagaStartedEvent   |
      | pack-car     | TxStartedEvent     |
      | pack-car     | TxEndedEvent       |
      | pack-hotel   | TxStartedEvent     |
      | pack-hotel   | TxAbortedEvent     |
      | pack-car     | TxCompensatedEvent |
      | pack-hotel   | TxCompensatedEvent |
      | pack-hotel   | SagaEndedEvent     |
      | pack-hotel   | TxEndedEvent       |
      | pack-booking | SagaEndedEvent     |

    Then Car Service contains the following booking orders
      | name  | amount | confirmed | cancelled |
      | Jason | 1      | false     | true      |

    And Hotel Service contains the following booking orders
      | name  | amount | confirmed | cancelled |
      | Jason | 1      | true      | false     |