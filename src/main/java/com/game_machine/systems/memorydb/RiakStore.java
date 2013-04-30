package com.game_machine.systems.memorydb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;

import com.basho.riak.client.IRiakClient;
import com.basho.riak.client.IRiakObject;
import com.basho.riak.client.RiakException;
import com.basho.riak.client.RiakFactory;
import com.basho.riak.client.RiakRetryFailedException;
import com.basho.riak.client.bucket.Bucket;
import com.basho.riak.client.cap.UnresolvedConflictException;
import com.basho.riak.client.convert.ConversionException;
import com.basho.riak.client.raw.pbc.PBClientConfig;

import akka.actor.Cancellable;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class RiakStore extends UntypedActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	private IRiakClient client;
	private String bucketName = "testBucket";
	private Bucket bucket;
	private Integer writeInterval = 5000;
	private Integer maxWritesPerSecond = 50;
	private Integer minWriteInterval = 1000 / maxWritesPerSecond;
	private Long lastWrite = System.currentTimeMillis();
	public HashMap<String, GameObject> gameObjects = new HashMap<String, GameObject>();
	public HashMap<String, Long> gameObjectUpdates = new HashMap<String, Long>();
	public ArrayList<GameObject> gameObjectsList = new ArrayList<GameObject>();
	public GameObject currentGameObject = null;

	public RiakStore() {
		initRiak();
		Cancellable cancellable = this
				.getContext()
				.system()
				.scheduler()
				.schedule(Duration.Zero(), Duration.create(minWriteInterval, TimeUnit.MILLISECONDS), this.getSelf(), "tick",
						this.getContext().system().dispatcher(), null);
	}

	public void initRiak() {
		PBClientConfig conf = new PBClientConfig.Builder().withHost("192.168.130.128").withPort(8087).build();
		try {
			client = RiakFactory.newClient(conf);
		} catch (RiakException e) {
			e.printStackTrace();
		}

		try {
			bucket = client.createBucket(bucketName).execute();
			bucket = client.fetchBucket(bucketName).execute();
			bucket.store("key1", "value1").execute();
			IRiakObject myData = bucket.fetch("key1").execute();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public Boolean writeGameObject(GameObject gameObject) {
		try {
			bucket.store("key1", gameObject).execute();
		} catch (RiakRetryFailedException | UnresolvedConflictException | ConversionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}

	public Boolean eligibleForWrite(GameObject gameObject) {
		Long lastUpdated = gameObjectUpdates.get(gameObject.getId());

		if ((System.currentTimeMillis() - lastUpdated) < writeInterval) {
			// Don't update a specific object more then once every writeInterval
			return false;
		} else {
			return true;
		}
	}

	public Boolean busy() {
		if ((System.currentTimeMillis() - lastWrite) < minWriteInterval) {
			return true;
		} else {
			return false;
		}
	}

	public void setGameObject(GameObject gameObject) {
		gameObjects.put(gameObject.getId(), gameObject);
		gameObjectUpdates.put(gameObject.getId(), System.currentTimeMillis());
	}

	public void onReceive(Object message) {
		if (message instanceof GameObject) {
			currentGameObject = (GameObject) message;
			Boolean writeThrough = true;

			if (gameObjects.containsKey(currentGameObject.getId())) {
				if (!busy() && eligibleForWrite(currentGameObject)) {
					writeThrough = true;
				} else {
					writeThrough = false;
				}
			} else if (busy()) {
				writeThrough = false;
			}

			gameObjects.put(currentGameObject.getId(), currentGameObject);
			if (writeThrough) {
				if (writeGameObject(currentGameObject)) {
					gameObjectUpdates.put(currentGameObject.getId(), System.currentTimeMillis());
				}
			}

		} else if (message instanceof String) {
			if (message.equals("tick")) {
				log.info("TICK");
				currentGameObject = new GameObject();
				if (busy()) {

				}
			}
		} else {
			unhandled(message);
		}
	}
}
