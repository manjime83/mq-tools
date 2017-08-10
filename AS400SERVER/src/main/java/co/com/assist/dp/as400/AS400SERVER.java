package co.com.assist.dp.as400;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.io.FileUtils;

import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;

public class AS400SERVER implements Runnable {

	public static void main(String[] args) {
		new AS400SERVER().run();
	}

	private String queueManagerName = null;
	private String requestQueueName = null;

	@SuppressWarnings("unchecked")
	public AS400SERVER() {
		try (InputStream in = new FileInputStream(AS400SERVER.class.getSimpleName() + ".config.xml")) {
			Properties properties = new Properties();
			properties.loadFromXML(in);

			MQEnvironment.hostname = properties.getProperty("hostname");
			MQEnvironment.port = Integer.parseInt(properties.getProperty("port"));
			MQEnvironment.channel = properties.getProperty("channel");
			MQEnvironment.userID = properties.getProperty("userID");

			MQEnvironment.properties.put(MQConstants.TRANSPORT_PROPERTY, MQConstants.TRANSPORT_MQSERIES);

			queueManagerName = properties.getProperty("queue.manager");
			requestQueueName = properties.getProperty("request.queue");
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}

	@Override
	public void run() {
		try {
			MQQueueManager queueManager = new MQQueueManager(queueManagerName);

			MQQueue requestQueue = queueManager.accessQueue(requestQueueName,
					MQConstants.MQOO_INPUT_AS_Q_DEF + MQConstants.MQOO_FAIL_IF_QUIESCING);
			MQGetMessageOptions gmo = new MQGetMessageOptions();
			gmo.options += MQConstants.MQGMO_FAIL_IF_QUIESCING;
			gmo.options += MQConstants.MQGMO_WAIT;
			gmo.waitInterval = MQConstants.MQWI_UNLIMITED;

			while (true) {
				MQMessage requestMessage = new MQMessage();
				requestQueue.get(requestMessage, gmo);

				try {
					String request = requestMessage.readStringOfByteLength(requestMessage.getMessageLength());
					if (request.trim().isEmpty()) {
						break;
					}
					System.out.println(request);

					String service = null;
					try {
						service = request.substring(13, 22);
					} catch (StringIndexOutOfBoundsException e) {
						e.printStackTrace();
						continue;
					}

					int index = 0;
					try {
						index = Integer.valueOf(request.substring(121, 123));
					} catch (StringIndexOutOfBoundsException | NumberFormatException e) {
						e.printStackTrace();
						continue;
					}

					File responseFile = new File(AS400SERVER.class.getSimpleName(), service + "." + index + ".txt");

					if (requestMessage.replyToQueueName.trim().isEmpty()) {
						System.err.println("replyToQ not set");
					} else if (!responseFile.exists()) {
						System.err.println("response file does not exists: " + responseFile.toString());
					} else {
						String response = FileUtils.readFileToString(responseFile, "UTF-8");
						System.out.println(response);

						MQQueue replyToQueue = queueManager.accessQueue(requestMessage.replyToQueueName,
								MQConstants.MQOO_OUTPUT + MQConstants.MQOO_FAIL_IF_QUIESCING);
						MQPutMessageOptions pmo = new MQPutMessageOptions();

						MQMessage responseMessage = new MQMessage();
						responseMessage.correlationId = requestMessage.messageId;
						responseMessage.format = MQConstants.MQFMT_STRING;
						responseMessage.expiry = 300 * 10; // 5 minutos
						responseMessage.writeString(response);

						replyToQueue.put(responseMessage, pmo);
					}
				} catch (EOFException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			requestQueue.close();

			queueManager.disconnect();
		} catch (MQException e) {
			e.printStackTrace();
		}
	}

}
