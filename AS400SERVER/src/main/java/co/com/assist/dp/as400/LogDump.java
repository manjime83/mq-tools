package co.com.assist.dp.as400;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;

public class LogDump implements Runnable {

	private static final Namespace env = Namespace.getNamespace("env", "http://schemas.xmlsoap.org/soap/envelope/");
	private static final Namespace aud = Namespace.getNamespace("aud",
			"http://www.colpatria.com/esb/services/commons/as/auditCreate/");

	private static final XMLOutputter prettyOutputter = new XMLOutputter(
			Format.getPrettyFormat().setOmitDeclaration(true).setIndent("\t"));

	public static void main(String[] args) {
		new LogDump().run();
	}

	private String queueManagerName = null;
	private String requestQueueName = null;

	@SuppressWarnings("unchecked")
	public LogDump() {
		try (InputStream in = new FileInputStream(AS400SERVER.class.getSimpleName() + ".config.xml")) {
			Properties properties = new Properties();
			properties.loadFromXML(in);

			MQEnvironment.hostname = properties.getProperty("hostname");
			MQEnvironment.port = Integer.parseInt(properties.getProperty("port"));
			MQEnvironment.channel = properties.getProperty("channel");
			MQEnvironment.userID = properties.getProperty("userID");

			MQEnvironment.properties.put(MQConstants.TRANSPORT_PROPERTY, MQConstants.TRANSPORT_MQSERIES);

			queueManagerName = properties.getProperty("queue.manager");
			requestQueueName = "COLPCO.AUDIT.DP.MGR.REQ";
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
					String str = requestMessage.readStringOfByteLength(requestMessage.getMessageLength());
					if (str.trim().isEmpty()) {
						break;
					}

					Document request = new SAXBuilder().build(new StringReader(str));

					Element messageElement = request.getRootElement().getChild("Body", env)
							.getChild("auditCreateRq", aud).getChild("message", aud);
					Document message = new SAXBuilder().build(new StringReader(messageElement.getText()));
					messageElement.removeContent();
					messageElement.addContent(message.getRootElement().detach());

					String prettyRequest = prettyOutputter.outputString(request);
					
					File logFile = new File("COLPCO.AUDIT.DP.MGR.REQ.log");
					FileUtils.writeStringToFile(logFile, prettyRequest, "UTF-8", true);
				} catch (IOException e) {
					e.printStackTrace();
				} catch (JDOMException e) {
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
