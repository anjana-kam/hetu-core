/*
 * Copyright (C) 2022-2022. Yijian Cheng. All rights reserved.
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
package io.hetu.core.plugin.mpp.scheduler.utils;

import io.hetu.core.plugin.mpp.RunningTaskHashMap;
import io.hetu.core.plugin.mpp.SynchronizedHashMap;
import io.hetu.core.plugin.mpp.scheduler.entity.ETLInfo;
import io.hetu.core.plugin.mpp.scheduler.entity.TableSchema;

public class Const
{
    private Const()
    {
    }

    /**
     * Thread Communication
     * tableName:String, status:int
     * status:0-have created hive table; 1-have finished exported data into table
     */
    public static SynchronizedHashMap<String, Integer> tableStatus = new SynchronizedHashMap<>("tableStatus");

    public static String idSeparator = "-";

    public static SynchronizedHashMap<String, ETLInfo> etlInfoMap = new SynchronizedHashMap<>("etlInfo");

    public static RunningTaskHashMap runningThreadMap = new RunningTaskHashMap();

    public static SynchronizedHashMap<String, TableSchema> schemasMap = new SynchronizedHashMap<>(100, 10000);
}
