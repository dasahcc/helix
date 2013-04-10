package org.apache.helix.taskexecution;

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

import java.io.File;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.I0Itec.zkclient.IDefaultNameSpace;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkServer;
import org.apache.commons.io.FileUtils;
import org.apache.helix.HelixManager;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.taskexecution.Dag.Node;

public class TaskExecutionDemo {

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			System.err
					.println("USAGE: java TaskExecutionDemo zkPort redisHost redisPort");
			System.exit(1);
		}

		String redisHost = args[1];
		int redisPort = Integer.parseInt(args[2]);
		ZkServer server = null;
		try {
			String baseDir = "/tmp/TaskExecutionDemo/";
			final String dataDir = baseDir + "zk/dataDir";
			final String logDir = baseDir + "/tmp/logDir";
			FileUtils.deleteDirectory(new File(dataDir));
			FileUtils.deleteDirectory(new File(logDir));

			IDefaultNameSpace defaultNameSpace = new IDefaultNameSpace() {
				@Override
				public void createDefaultNameSpace(ZkClient zkClient) {

				}
			};

			int zkPort = Integer.parseInt(args[0]);
			server = new ZkServer(dataDir, logDir, defaultNameSpace, zkPort);
			server.start();

			String zkAddr = "localhost:" + zkPort;
			String clusterName = TaskCluster.DEFAULT_CLUSTER_NAME;
			
			TaskCluster taskCluster = new TaskCluster(zkAddr, clusterName);
			taskCluster.setup();

			startController(zkAddr, clusterName);

			TaskFactory taskFactory = new AnalyticsTaskFactory();
			TaskResultStore taskResultStore = new RedisTaskResultStore(redisHost, redisPort, 1000);
			
			populateDummyData(taskResultStore);

			startWorkers(zkAddr, TaskCluster.DEFAULT_CLUSTER_NAME, taskFactory, taskResultStore);

			Dag dag = getAnalyticsDag();
			taskCluster.submitDag(dag);
		} finally {
			if (server != null) {
				// server.shutdown();
			}
		}
	}

	private static void populateDummyData(TaskResultStore taskResultStore) throws Exception {
		float fraudProbability = 0.01f;
		float clickProbability = 0.01f;
		int numImps = 10000; 
		Random rand = new Random();
		String[] countries = {"US", "CANADA", "UK", "CHINA", "UNKNOWN"};
		String[] genders = {"M", "F", "UNKNOWN"};
		for(int i = 0; i < numImps; i++) {
			boolean isFraudulent = (rand.nextFloat() <= fraudProbability);
			String impEventId = "" + Math.abs(rand.nextLong());
			String impEvent = impEventId; //event id
			impEvent += "," + isFraudulent;
			impEvent += "," + countries[rand.nextInt(countries.length)];
			impEvent += "," + genders[rand.nextInt(genders.length)];
			taskResultStore.rpush(FilterTask.IMPRESSIONS, impEvent);
			
			boolean isClick = (rand.nextFloat() <= clickProbability);
			if(isClick) {
				String clickEvent = "" + Math.abs(rand.nextLong()); //event id
				isFraudulent = (rand.nextFloat() <= fraudProbability);
				clickEvent += "," + isFraudulent;
				clickEvent += "," + impEventId;
				taskResultStore.rpush(FilterTask.CLICKS, clickEvent);
			}
		}
		System.out.println("Done populating dummy data");
	}

	private static void startController(String zkAddr, String clusterName)
			throws Exception {
		final HelixManager manager = HelixControllerMain.startHelixController(
				zkAddr, clusterName, null, HelixControllerMain.STANDALONE);

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				System.out.println("Shutting down cluster manager: "
						+ manager.getInstanceName());
				manager.disconnect();
			}
		});
	}

	private static void startWorkers(String zkAddr, String clusterName,
			TaskFactory taskFactory, TaskResultStore taskResultStore) {
		int numWorkers = 10;
		Executor executor = Executors.newFixedThreadPool(numWorkers);

		for (int i = 0; i < numWorkers; i++) {
			Worker worker = new Worker(zkAddr, clusterName, "" + i, taskFactory, taskResultStore);
			executor.execute(worker);
		}
	}

	
	private static Dag getAnalyticsDag() {
		Dag dag = new Dag();
		dag.addNode(new Node("filterImps", 5, ""));
		dag.addNode(new Node("filterClicks", 5, ""));
		dag.addNode(new Node("impClickJoin", 5, "filterImps,filterClicks"));
		dag.addNode(new Node("impCountsByGender", 5, "filterImps"));
		dag.addNode(new Node("impCountsByCountry", 5, "filterImps"));
		dag.addNode(new Node("clickCountsByGender", 5, "impClickJoin"));
		dag.addNode(new Node("clickCountsByCountry", 5, "impClickJoin"));
		
		dag.addNode(new Node("report", 1, "impCountsByGender,impCountsByCountry,clickCountsByGender,clickCountsByCountry"));
		
		return dag;
	}

}