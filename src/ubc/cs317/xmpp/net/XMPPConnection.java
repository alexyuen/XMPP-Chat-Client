/*
 * University of British Columbia
 * Department of Computer Science
 * CPSC317 - Internet Programming
 * Assignment 1
 * 
 * Author: Alex Yuen
 * January 2012
 * 
 * This code may not be used without written consent of the authors, except for 
 * current and future projects and assignments of the CPSC317 course at UBC.
 */

package ubc.cs317.xmpp.net;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.xml.bind.DatatypeConverter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ubc.cs317.xmpp.exception.XMPPException;
import ubc.cs317.xmpp.model.Contact;
import ubc.cs317.xmpp.model.ContactStatus;
import ubc.cs317.xmpp.model.Message;
import ubc.cs317.xmpp.model.Session;

/**
 * This class describes the XMPP connection handler. A socket connection is
 * created when an instance of this handler is created, and methods are provided
 * for most common operations in XMPP.
 * 
 * This class will not in any case make a direct reference to any class or
 * method that represents a specific UI library.
 */
public class XMPPConnection {

	/**
	 * Default TCP port for client-server communication in XMPP.
	 */
	public static final int XMPP_DEFAULT_PORT = 5222;

	/**
	 * Session object for communication between the network component and the
	 * chat model and UI.
	 */
	private Session session;

	/**
	 * Socket object associated to the communication between this client and the
	 * XMPP server.
	 */
	private Socket socket;

	/**
	 * XMPP reader helper, used to obtain XML nodes from the XMPP stream.
	 */
	private XMPPStreamReader xmppReader;

	/**
	 * XMPP writer helper, used to write XML nodes to the XMPP stream.
	 */
	private XMPPStreamWriter xmppWriter;

	/**
	 * Features element that the server sends us upon successful stream initialization
	 */
	private Element features;
	
	/**
	 * id counter for info queries
	 */
	private int idCounter;
	
	
	
	/**
	 * Creates a new instance of the connection handler. This constructor will
	 * creating the socket, initialise the reader and writer helpers, send
	 * initial tags, authenticate the user and bind to a resource.
	 * 
	 * @param jidUser
	 *            User part of the Jabber ID.
	 * @param jidDomain
	 *            Domain part of the Jabber ID.
	 * @param resource
	 *            Resource to bind once authenticated. If null or empty, a new
	 *            resource will be generated.
	 * @param password
	 *            Password for authentication.
	 * @param session
	 *            Instance of the session to communicate with other parts of the
	 *            system.
	 * @throws XMPPException
	 *             If there is an error establishing the connection, sending or
	 *             receiving necessary data, or while authenticating.
	 */
	public XMPPConnection(String jidUser, String jidDomain, String resource,
			String password, Session session) throws XMPPException {

		this.session = session;

		initializeConnection(jidDomain);

		try {
			xmppReader = new XMPPStreamReader(socket.getInputStream());
			xmppWriter = new XMPPStreamWriter(socket.getOutputStream());
		} catch (XMPPException e) {
			throw e;
		} catch (Exception e) {
			throw new XMPPException("Could not obtain socket I/O channels ("
					+ e.getMessage() + ")", e);
		}

		initializeStreamAndFeatures(jidUser, jidDomain);

		login(jidUser, password);

		bindResource(resource);

		startListeningThread();
	}

	/**
	 * Initialises the connection with the specified domain. This method sets
	 * the socket field with an initialised socket.
	 * 
	 * @param domain
	 *            DNS name (or IP string) of the server to connect to.
	 * @throws XMPPException
	 *             If there is a problem connecting to the server.
	 */
	private void initializeConnection(String domain) throws XMPPException {

		//Create socket connection
		try {
			socket = new Socket(domain, XMPP_DEFAULT_PORT);
		} catch (UnknownHostException e) {
			e.printStackTrace();
			throw new XMPPException("Error: Unknown host", e);
		} catch (IOException e) {
			e.printStackTrace();
			throw new XMPPException("Error: I/O Exception", e);
		}

	}

	/**
	 * Sends the initial data to establish an XMPP connection stream with the
	 * XMPP server. This method also retrieves the set of features from the
	 * server, saving it in a field for future use.
	 * 
	 * @param jidUser
	 *            User part of the Jabber ID.
	 * @param jidDomain
	 *            Domain part of the Jabber ID.
	 * @throws XMPPException
	 *             If there is a problem sending or receiving the data.
	 */
	private void initializeStreamAndFeatures(String jidUser, String jidDomain)
			throws XMPPException {
		Element stream = xmppWriter.createRootElement("stream:stream");
		stream.setAttribute("from", jidUser + "@" + jidDomain);
		stream.setAttribute("to", jidDomain);
		stream.setAttribute("version", "1.0");
		stream.setAttribute("xml:lang", "en");
		stream.setAttribute("xmlns", "jabber:client");
		stream.setAttribute("xmlns:stream", "http://etherx.jabber.org/streams");

		xmppWriter.writeRootElementWithoutClosingTag();
		//xmppWriter.debugElement(System.out, stream);
		features = xmppReader.readSecondLevelElement();
		xmppWriter.debugElement(System.out, features);
	}

	/**
	 * Attempts to authenticate the user name with the provided password at the
	 * connected server. This method will verify if the server supports the
	 * implemented authentication mechanism(s) and send the user and password
	 * based on the first mechanism it finds. In case authentication is not
	 * successful, this function will close the connection and throw an
	 * XMPPException. This function also retrieves the new set of features
	 * available after authentication.
	 * 
	 * @param username
	 *            User name to use for authentication.
	 * @param password
	 *            Password to use for authentication.
	 * @throws XMPPException
	 *             If authentication is not successful, or if authentication
	 *             methods supported by the server are not implemented, or if
	 *             there was a problem sending authentication data.
	 */
	private void login(String username, String password) throws XMPPException {
		// get the list of supported login mechanisms
		NodeList mechs = features.getElementsByTagName("mechanism");
		// iterate over the list for the ones that we want
		for (int i = 0; i < mechs.getLength(); i++) {
			if (mechs.item(i).getTextContent().equals("PLAIN")) {
				// create auth element and add data
				Element auth = xmppWriter.createElement("auth");
				auth.setAttribute("xmlns", "urn:ietf:params:xml:ns:xmpp-sasl");
				auth.setAttribute("mechanism", "PLAIN");
				byte[] data = ("\u0000" + username + "\u0000" + password).getBytes();
				String base64Encoded = DatatypeConverter.printBase64Binary(data);
				auth.setTextContent(base64Encoded);
				xmppWriter.writeIndividualElement(auth);

				// check to see if authentication was successful
				Element reply = xmppReader.readSecondLevelElement();
				xmppWriter.debugElement(System.out, reply);
				if (reply.getTagName().equals("failure")) {
					closeConnection();
					Node reason = reply.getFirstChild();
					throw new XMPPException("Authentication failed: "  + reason.getNodeName());
				} else {
					// // as required by RFC 6120, the initial stream header will be sent again after successful SASL negotiation 
					// resend initial stream header without closing the stream
					xmppWriter.writeRootElementWithoutClosingTag();
				}
				return;
			}
		}
		throw new XMPPException("Server does not support LOGIN authentication mechanism.");
	}

	/**
	 * Binds the connection to a specific resource, or retrieves a
	 * server-generated resource if one is not provided. This function will wait
	 * until a resource is sent by the server.
	 * 
	 * @param userResource
	 *            Name of the user-specified resource. If resource is null or
	 *            empty, retrieves a server-generated one.
	 * @throws XMPPException
	 *             If there is an error sending or receiving the data.
	 */
	private void bindResource(String userResource) throws XMPPException {
		// read incoming bind features
		Element test = xmppReader.readSecondLevelElement();
		xmppWriter.debugElement(System.out, test);
		
		// create iq element to request a bind
		Element iq = xmppWriter.createElement("iq");
		iq.setAttribute("type", "set");
		iq.setAttribute("id", generateStanzaID());
		
		Element bind = xmppWriter.createElement("bind");
		bind.setAttribute("xmlns", "urn:ietf:params:xml:ns:xmpp-bind");

		iq.appendChild(bind);

		// if user specified a resource, add it as a text node of a resource child to the bind element
		if (userResource != null && userResource.length() > 0) {
			Element r = xmppWriter.createElement("resource");
			r.setTextContent(userResource);
			bind.appendChild(r);
		}
		//xmppWriter.debugElement(System.out, iq);
		xmppWriter.writeIndividualElement(iq);

		// check to see if bind was successful
		Element reply = xmppReader.readSecondLevelElement();
		xmppWriter.debugElement(System.out, reply);
		if (reply.getAttribute("type").equals("result")) {
			String jid = reply.getElementsByTagName("jid").item(0).getTextContent();
			session.setUserJid(jid);
		} else {
			//String errorType = reply.getFirstChild().getFirstChild().getNodeName();
			throw new XMPPException("Resource binding failed.");
		}
	}

	/**
	 * Starts a thread that will keep listening for new messages asynchronously
	 * from the main thread.
	 */
	private void startListeningThread() {
		Thread listeningThread = new Thread(new Runnable() {
			@Override
			public void run() {
				listeningProcess();
			}
		});
		listeningThread.start();
	}

	/**
	 * Keeps listening for new XML elements in a loop until a closing tag is
	 * found or an exception happens. If an exception happens, calls
	 * <code>session.processReceivedException</code> and closes the connection.
	 * If the closing tag is found, sends a closing tag back and closes the
	 * connection as well. In both cases, the connection is closed at the
	 * session level, so that the model and UI can handle the connection being
	 * closed. For each received element, processes the element and handles it
	 * accordingly.
	 */
	private void listeningProcess() {

		boolean active = true;
		while (active) {
			try {
				Element incoming = xmppReader.readSecondLevelElement();
				xmppWriter.debugElement(System.out, incoming);

				if (incoming.getAttribute("type").equals("error")) {
					// error handling
					Element errorElement = (Element) incoming.getFirstChild();
					String errorType = errorElement.getAttribute("type");
					if (errorType.equals("auth")) {
						// retry after providing credentials
						throw new XMPPException("You are not authorized to perform that action.");
					} else if (errorType.equals("cancel")) {
						// no way to recover from this error
						throw new XMPPException("The last operation was cancelled by the server: " + errorElement.getFirstChild().getNodeName());
					} else if (errorType.equals("modify")) {
						// retry after changing the data sent 
						throw new XMPPException("Invalid data was sent to the server: " + errorElement.getFirstChild().getNodeName());
					} else if (errorType.equals("continue")) {
						// proceed (the condition was only a warning) 
					} else if (errorType.equals("wait")) {
						// retry after waiting (the error is temporary) 
					} else {
						// this is an unsupported error type
						throw new XMPPException("Unsupported error type was received from the server: " + errorElement.getFirstChild().getNodeName());
					}
				}

				if (xmppReader.isDocumentComplete()) {
					// close tag received
					session.closeConnection();
					return;
				} else if (incoming.getTagName().equals("message")) {
					// a contact has sent a message
					if (incoming.getAttribute("type").equals("chat")) {
						// parse the attributes
						String from = incoming.getAttribute("from");
						String resource = (from.split("/").length > 1 ? from.split("/")[1] : null);
						
						Contact contact = session.getContact(from); 
						// possibly contact will be null if they didn't delete us
						if (contact == null) {
							contact = new Contact(from, from.split("@")[0]);
						}
						
						// create objects to store the data
						Element body = (Element) incoming.getElementsByTagName("body").item(0);
						// possible body == null
						if (body == null)
							continue;
						Message m = new Message(contact, null, body.getTextContent()); 
						session.getConversation(contact).addIncomingMessage(m, resource);
					} 
					// no need to worry about other types
					// http://xmpp.org/rfcs/rfc6121.html#message-syntax-type

				} else if (incoming.getTagName().equals("presence")) {
					// get the jid
					String from = incoming.getAttribute("from");
					// split the resource from the jid
					String resource = (from.split("/").length > 1 ? from.split("/")[1] : null);
					
					String type = incoming.getAttribute("type");
					if (type.equals("unavailable")) {
						// a contact has logged off a resource
						session.getContact(from).setStatus(resource, ContactStatus.OFFLINE);
					} else if (type.equals("subscribe")) {
						// a potential contact has requested permission to this user's presence status
						session.handleReceivedSubscriptionRequest(from);
					} else if (type.equals("unsubscribed")) {
						// a contact has requested we unsubscribe from their presence
						if (session.getContact(from) != null)
							removeAndUnsubscribeContact(session.getContact(from));
					} else if (type.equals("unsubscribe")) {
						// a contact has unsubscribed to our presence
						if (session.getContact(from) != null)
							removeAndUnsubscribeContact(session.getContact(from));
					} else if (type.equals("subscribed")) {
						// successful subscription, do nothing
					} else if (incoming.hasChildNodes()) {
						// http://xmpp.org/rfcs/rfc6121.html#presence-fundamentals
						if (session.getContact(from) == null)
							continue;
						// a contact has changed his status
						Element show = (Element) incoming.getElementsByTagName("show").item(0);
						if (show != null) {
							session.getContact(from).setStatus(resource, ContactStatus.getContactStatus(show.getTextContent()));
						} else if (!from.split("@")[0].equals(session.getUserJid().split("@")[0])) {
							session.getContact(from).setStatus(resource, ContactStatus.AVAILABLE);
						}
					}
				} else if (incoming.getTagName().equals("iq")) {
					// a result has been returned
					
					if (incoming.getAttribute("type").equals("result")) {
						Element query = (Element) incoming.getFirstChild();
						if (query == null) {
							// do nothing if iq is empty (ie http://xmpp.org/rfcs/rfc6121.html#roster-add-success )
							continue;
						}
						if (query.getNodeName().equals("query") && query.getAttribute("xmlns").equals("jabber:iq:roster")) {
							// contact list was received from the server
							NodeList contacts = query.getElementsByTagName("item");

							//update the contact list with the list of contacts received
							for (int i = 0; i < contacts.getLength(); i++) {
								Element item = (Element) contacts.item(i);
								addReceivedContact(item);
							}
							System.out.println("Contact list received.");
						}
					} else if (incoming.getAttribute("type").equals("set")) {
						// change the roster (ie add or remove a contact)
						Element item = (Element) incoming.getElementsByTagName("item").item(0);
						if (item.getAttribute("ask").equals("subscribe")) {
							// http://xmpp.org/rfcs/rfc6121.html#sub-request-outbound
							// // add a contact
							addReceivedContact(item);
						} else if (item.getAttribute("subscription").equals("remove")) {
							// remove a contact
							String jid = item.getAttribute("jid");
							if (session.getContact(jid) != null)
								session.removeContact(session.getContact(jid));
						} else if (!item.getAttribute("subscription").equals("none")) {
							// add a contact
							addReceivedContact(item);
						}
					} else if (incoming.getAttribute("type").equals("get")) {
						String from = incoming.getAttribute("from");
						Element child = (Element) incoming.getFirstChild();
						
						if (child.getTagName().equals("ping")) {
							// ping received; send pong
							Element iq = xmppWriter.createElement("iq");
							iq.setAttribute("from", session.getUserJid());
							iq.setAttribute("to", from);
							iq.setAttribute("type", "result");
							xmppWriter.writeIndividualElement(iq);
						}
					}
				}
			} catch (XMPPException e) {
				session.processReceivedException(new XMPPException(e));
				session.closeConnection();
				return;
			}
		}
	}
	
	/**
	 * Adds a received contact Element to the session
	 * @param item The Element with tagname "item" containing attributes which define the contact
	 */
	public void addReceivedContact(Element item) {
		// get the contact's jid
		String jid = item.getAttribute("jid");

		// only add the contact if he is not already in the contact list
		if (session.getContact(jid) == null) {
			String name = item.getAttribute("name");
			// check if a "friendly name" was given
			if (!name.equals("")) {
				session.addReceivedContact(new Contact(jid, name));
			} else {
				// otherwise use the first part of the jid as the friendly name
				session.addReceivedContact(new Contact(jid, jid.split("@")[0]));
			}
		}
	}

	/**
	 * Closes the connection. If the connection was already closed before this
	 * method is called nothing is done, otherwise sends all necessary closing
	 * data and waits for the server to send the closing data as well. Once this
	 * happens the socket connection is closed. This method does not throw any
	 * exception, choosing instead to ignore them. However, even if an exception
	 * happens while sending the final data, the socket connection will be
	 * closed.
	 */
	public synchronized void closeConnection() {
		if (socket.isClosed()) {
			return;
		}
		try {
			xmppWriter.writeCloseTagRootElement();
			xmppReader.waitForCloseDocument();
		} catch (XMPPException ignored) {		
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
			}
		}
	}

	/**
	 * Sends a request for the contact list. The result is not expected to be
	 * received in this function, but it should come in a message that will be
	 * handled by the listening process.
	 * 
	 * @throws XMPPException
	 *             If there was a problem sending the request.
	 */
	public void sendRequestForContactList() throws XMPPException {
		Element iq = xmppWriter.createElement("iq");
		iq.setAttribute("from", session.getUserJid());
		iq.setAttribute("type", "get");
		iq.setAttribute("id", generateStanzaID());
		
		Element query = xmppWriter.createElement("query");
		query.setAttribute("xmlns", "jabber:iq:roster");
		
		iq.appendChild(query);
		
		xmppWriter.writeIndividualElement(iq);
	}

	/**
	 * Sends an updated status information to the server, based on the status
	 * currently attributed to the session.
	 * 
	 * @throws XMPPException
	 *             If there was a problem sending the status.
	 */
	public void sendCurrentStatus() throws XMPPException {
		sendStatus(session.getCurrentStatus());
	}

	/**
	 * Sends a specific status information to the server.
	 * 
	 * @param status
	 *            Status to send to the server.
	 * @throws XMPPException
	 *             If there was a problem sending the status.
	 */
	private void sendStatus(ContactStatus status) throws XMPPException {
		// http://xmpp.org/rfcs/rfc6121.html#presence-fundamentals
		Element presence = xmppWriter.createElement("presence");
		switch (status) {
		case AVAILABLE:
			// add nothing to the presence
			break;
		case OFFLINE:
			// add type=unavailable to the presence
			presence.setAttribute("type", "unavailable");
			break;
		default:
			Element show = xmppWriter.createElement("show");
			show.setTextContent(status.getXmppShow());
			presence.appendChild(show);
		}
		// send the presence
		xmppWriter.writeIndividualElement(presence);
	}

	/**
	 * Sends a request that a new contact be added to the list of contacts.
	 * Additionally, requests authorization from that contact to receive updates
	 * any time the contact changes its status. This function does not add the
	 * user to the local list of contacts, which happens at the listening
	 * process once the server sends an update to the list of contacts as a
	 * result of this request.
	 * 
	 * @param contact
	 *            Contact that should be requested.
	 * @throws XMPPException
	 *             If there is a problem sending the request.
	 */
	public void sendNewContactRequest(Contact contact) throws XMPPException {
		// http://xmpp.org/rfcs/rfc6121.html#roster-add
		
		// send iq adding new contact to roster
		Element iq = xmppWriter.createElement("iq");
		iq.setAttribute("from", session.getUserJid());
		iq.setAttribute("type", "set");
		
		Element query = xmppWriter.createElement("query");
		query.setAttribute("xmlns", "jabber:iq:roster");
		
		Element item = xmppWriter.createElement("item");
		item.setAttribute("jid", contact.getBareJid());
		item.setAttribute("name", contact.getAlias());
		
		iq.appendChild(query);
		query.appendChild(item);
		
		xmppWriter.writeIndividualElement(iq);
		
		// request a subscription
		Element presence = xmppWriter.createElement("presence");
		presence.setAttribute("to", contact.getBareJid());
		presence.setAttribute("type", "subscribe");
		presence.setAttribute("id", generateStanzaID());
		
		xmppWriter.writeIndividualElement(presence);
	}

	/**
	 * Sends a response message to a contact that requested authorization to
	 * receive updates when the local user changes its status.
	 * 
	 * @param jid
	 *            Jabber ID of the contact that requested authorization.
	 * @param accepted
	 *            <code>true</code> if the request was accepted by the user,
	 *            <code>false</code> otherwise.
	 * @throws XMPPException
	 *             If there was an error sending the response.
	 */
	public void respondContactRequest(String jid, boolean accepted)
			throws XMPPException {
		Element presence = xmppWriter.createElement("presence");
		presence.setAttribute("to", jid);
		presence.setAttribute("id", generateStanzaID());
		if (accepted) {
			presence.setAttribute("type", "subscribed");
		} else {
			presence.setAttribute("type", "unsubscribed");
		}
		
		xmppWriter.writeIndividualElement(presence);
	}

	/**
	 * Request that the server remove a specific contact from the list of
	 * contacts. Additionally, requests that no further status updates be sent
	 * regarding that contact, as well as that no further status updates about
	 * the local user be sent to that contact. This function does not remove the
	 * user from the local list of contacts, which happens at the listening
	 * process once the server sends an update to the list of contacts as a
	 * result of this request.
	 * 
	 * @param contact
	 *            Contact to be removed from the list of contacts.
	 * @throws XMPPException
	 *             If there was an error sending the request.
	 */
	public void removeAndUnsubscribeContact(Contact contact)
			throws XMPPException {
		// http://xmpp.org/rfcs/rfc6121.html#roster-delete
		Element iq = xmppWriter.createElement("iq");
		iq.setAttribute("type", "set");
		iq.setAttribute("id", generateStanzaID());
		
		Element query = xmppWriter.createElement("query");
		query.setAttribute("xmlns", "jabber:iq:roster");
		
		Element item = xmppWriter.createElement("item");
		item.setAttribute("jid", contact.getBareJid());
		item.setAttribute("subscription", "remove");
		
		iq.appendChild(query);
		query.appendChild(item);
		
		xmppWriter.writeIndividualElement(iq);
		
		// TODO: is this even needed?
		// request the contact be unsubscribed from our presence
		Element cancelSub = xmppWriter.createElement("presence");
		cancelSub.setAttribute("to", contact.getBareJid());
		cancelSub.setAttribute("type", "unsubscribed");
		cancelSub.setAttribute("id", generateStanzaID());
		xmppWriter.writeIndividualElement(cancelSub);
		
		// unsubscribe from the contact's presence
		Element unsubscribe = xmppWriter.createElement("presence");
		unsubscribe.setAttribute("to", contact.getBareJid());
		unsubscribe.setAttribute("type", "unsubscribe");
		cancelSub.setAttribute("id", generateStanzaID());
		xmppWriter.writeIndividualElement(unsubscribe);
		
		session.removeContact(contact);
	}

	/**
	 * Send a chat message to a specific contact.
	 * 
	 * @param message
	 *            Message to be sent.
	 * @throws XMPPException
	 *             If there was a problem sending the message.
	 */
	public void sendMessage(Message m) throws XMPPException {
		// create message element and fill in attributes
		Element message = xmppWriter.createElement("message");
		message.setAttribute("from", session.getUserJid());
		message.setAttribute("to", m.getTo().getFullJid());
		message.setAttribute("id", generateStanzaID());
		message.setAttribute("type", "chat");
		message.setAttribute("xml:lang", "en");
		
		// create body element
		Element body = xmppWriter.createElement("body");
		body.setTextContent(m.getTextMessage());
		message.appendChild(body);
		
		xmppWriter.writeIndividualElement(message);
	}
	
	private String generateStanzaID() {
		return "abc" + (idCounter++);
	}
}
