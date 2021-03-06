/*
 * Copyright (c) 2020 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.processor.tcp.tracker

sealed class RequestTrackerType {

    data class Tracker(val hostName: String) : RequestTrackerType() {
        override fun toString(): String = "Tracker: $hostName"
    }

    data class TrackerDelayedBlock(val hostName: String) : RequestTrackerType() {
        override fun toString(): String = "Delayed Tracker: $hostName"
    }

    data class NotTracker(val hostName: String) : RequestTrackerType() {
        override fun toString(): String = "Not a tracker: $hostName"
    }

    object Undetermined : RequestTrackerType() {
        override fun toString(): String = "Undetermined"
    }
}
