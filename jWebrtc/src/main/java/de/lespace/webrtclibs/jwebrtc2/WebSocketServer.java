package de.lespace.webrtclibs.jwebrtc2;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.kurento.client.EndOfStreamEvent;
import org.kurento.client.EventListener;
import org.kurento.client.FaceOverlayFilter;
import org.kurento.client.IceCandidate;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @ServerEndpoint gives the relative name for the end point This will be
 *                 accessed via ws://localhost:8080/jWebrtc/ws Where
 *                 "localhost" is the address of the host, "jWebrtc" is the
 *                 name of the package and "ws" is the address to access this
 *                 class from the server
 */
@ServerEndpoint("/ws")
public class WebSocketServer {

	private static final Gson gson = new GsonBuilder().create();
	
        private static final ConcurrentHashMap<String, MediaPipeline> pipelines = new ConcurrentHashMap<String, MediaPipeline>();
	
        public static UserRegistry registry = new UserRegistry();
	
        private static final String USER_STATUS_BUSY = "busy";
	private static final String USER_STATUS_OFFLINE = "offline";
	private static final String USER_STATUS_ONLINE = "online";
        
        private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);
        
        private void printCurrentUsage(){
            try{
                Enumeration<MediaPipeline> e = pipelines.elements();
                
                log.error("current pipelines size():"+pipelines.size());
                
                while(e.hasMoreElements()){
                    MediaPipeline mp = e.nextElement();
                    if(mp!=null) 
                        log.error("current pipeline:"+((mp.getName()!=null)?mp.getName():"NULL" )+ " : "+mp.getId());
                }
                
                log.error("current sessions keys:"+registry.getRegisteredUsers());
                log.error("current UserSession size:"+registry.getUserSessions().size());
                Iterator<UserSession> i  = registry.getUserSessions().iterator();
                while(i.hasNext()){
                    UserSession us = i.next();
                    if(us!=null) log.error("current user:"+us.getName()+" -  "+us.getSessionId());
                }
             }catch(Exception ex){
                         log.error("General printCurrentUsage Error:"+ex.getMessage());
             }
        }
	@OnOpen
	public void onOpen(Session session) {
		log.debug("apprtcWs opened with sessionId {}", session.getId());
                UserSession newUser = new UserSession(session, "webuser@"+session.getId());
		registry.register(newUser);
		printCurrentUsage();
	}

        
	@OnError
	public void onError(Session session, Throwable error) {
		log.error("Websocket Error [{}]", session.getId());
		error.getStackTrace();
                log.error(error.getMessage());
                error.printStackTrace();
                printCurrentUsage();
                
		if (error != null) {
			// System.err.println(" error:"+ error);
			log.error("Error: {}", error.getLocalizedMessage());
		}
	}

	/**
	 * The user closes the connection. Note: you can't send messages to the
	 * client from this method
	 */
	@OnClose
	public void onClose(Session session) {
		log.error("apprtcWs closed connection [{}]", session.getId());
                printCurrentUsage();
                UserSession user = registry.getBySession(session);
		try {
			publishOnlineStatus(user.getName(), USER_STATUS_OFFLINE);
		} catch (IOException e) {
                        log.error(e.getLocalizedMessage(), e);
		}
                
		try {
			stop(session,false);
                        String sessionId = session.getId();
                        killUserSession(session);
		} catch (IOException ex) {
			log.error(ex.getLocalizedMessage(), ex);
		}
                printCurrentUsage();
	}

	/**
	 * When a user sends a message to the server, this method will intercept the
	 * message and allow us to react to it. For now the message is read as a
	 * String.
	 * 
	 * @param _message
	 *            the json message
	 * @param session
	 *            the websocket session
	 */
	@OnMessage
	public void onMessage(String _message, Session session) {

		log.debug("apprtcWs [{}] received message: {}", session.getId(), _message);
		JsonObject jsonMessage = gson.fromJson(_message, JsonObject.class);
		UserSession userSession = registry.getBySession(session);

		if (userSession != null) {
			log.debug("Incoming message from user '{}': {}", userSession.getName(), jsonMessage);
		} else {
			log.debug("Incoming message from new user: {}", jsonMessage);
		}

		switch (jsonMessage.get("id").getAsString()) {
		case "appConfig":
			try {
				appConfig(session, jsonMessage);
                                log.debug("appConfig requested...");
			} catch (IOException e) {
				handleErrorResponse(e, session, "appConfigResponse");
			}
			break;
		case "register":
			try {
                            
                                boolean registered = register(session, jsonMessage);
                                
				if(registered) {
					userSession = registry.getBySession(session);
					sendRegisteredUsers();
					publishOnlineStatus(userSession.getName(), USER_STATUS_ONLINE);
				}
				
			} catch (Exception e) {
				handleErrorResponse(e, session, "registerResponse");
			}
			break;
		case "call":
			try {
				call(userSession, jsonMessage);
                                printCurrentUsage();
			} catch (Exception e) {
				handleErrorResponse(e, session, "callResponse");
			}
			break;
		case "incomingCallResponse":
			try {
				incomingCallResponse(userSession, jsonMessage);
			} catch (IOException ex) {
				// Logger.getLogger(WebSocketServer.class.getName()).log(Level.SEVERE,
				// null, ex);
				log.error(ex.getLocalizedMessage(), ex);
			}
			break;
		case "onIceCandidate":

			if (userSession != null) {
				JsonObject candidateJson = null;
				IceCandidate candidate = null;

				if (jsonMessage.has("sdpMLineIndex") && jsonMessage.has("sdpMLineIndex")) {
					// this is how it works when it comes from a android
					log.debug("apprtcWs candidate is coming from android or ios");
					candidateJson = jsonMessage;

				} else {
					// this is how it works when it comes from a browser
					log.debug("apprtcWs candidate is coming from web");
					candidateJson = jsonMessage.get("candidate").getAsJsonObject();
				}
                              //  log.info(jsonMessage.toString());

				candidate = new IceCandidate(candidateJson.get("candidate").getAsString(),
						candidateJson.get("sdpMid").getAsString(), candidateJson.get("sdpMLineIndex").getAsInt());
				
                                log.debug(candidate.getCandidate());
                                userSession.addCandidate(candidate);
			}
			break;
		case "stop":
			try {
                                log.error("received stop closing media piplines callback:"+jsonMessage.has("callback"));
				stop(session,jsonMessage.has("callback"));
                                printCurrentUsage();
                            } catch (IOException ex) {
                                ex.printStackTrace();
				log.error(ex.getLocalizedMessage(), ex);
                            }
			break;
                case "callback":
                        log.error("got callback myName is:"+userSession.getName()+" originalCall from:"+userSession.getCallingFrom()+" to:"+userSession.getCallingTo());
                        String from = userSession.getCallingFrom();
                        String to =  userSession.getCallingTo();
                        String myName = userSession.getName();
                       
                        JsonObject callBackMessage = new JsonObject();
                        callBackMessage.addProperty("id", "callback");
                        try {
                            if(from==null || myName.equals(from)){  //this from seems to be null sometimes... not clear why!
                                registry.getByName(to).sendMessage(callBackMessage);
                            }else{
                                registry.getByName(from).sendMessage(callBackMessage);
                            }
                        } catch (IOException ex) {
				log.error(ex.getLocalizedMessage(), ex);
                        }
                
			break;        
                case "checkOnlineStatus":
			try {
				queryOnlineStatus(session, jsonMessage);
			} catch (IOException e) {
                            log.error(e.getLocalizedMessage(), e);
                            e.printStackTrace();
			}
			break;
		case "play":
			play(userSession, jsonMessage);
			break;
		case "stopPlay":
			releasePipeline(userSession);
			break;
		default:
			break;
		}
	}
        
        /**
         * determine one of the status OFFLINE, BUSY, or ONLINE of 
         * the user given in the jsonMessage
         * and sends the answer back to the calling session (wether or not the user is registered)
        */ 
	private void queryOnlineStatus(Session session, JsonObject jsonMessage) throws IOException {
            
		String user = jsonMessage.get("user").getAsString();
		JsonObject responseJSON = new JsonObject();
		responseJSON.addProperty("id", "responseOnlineStatus");
                UserSession myUserSession = registry.getBySession(session);
                responseJSON.addProperty("myUsername",myUserSession.getName());
		UserSession userSession = registry.getByName(user);
		if (userSession == null) {
			responseJSON.addProperty("response", USER_STATUS_OFFLINE);
		} else {
			if (userSession.isBusy()) {
				responseJSON.addProperty("response", USER_STATUS_BUSY);
			} else {
				responseJSON.addProperty("response", USER_STATUS_ONLINE);
			}
		}
		responseJSON.addProperty("message", user);

                if(session.isOpen()){
                     log.debug("sending message:"+responseJSON.toString());
                     session.getBasicRemote().sendText(responseJSON.toString());//responseJSON.getAsString()

                }  
                else log.debug("session {} is closed.", session.getId());
	}
	
	/**
	 * Publishes the online status of the given user to all other users.
	 * 
	 * @param user
	 * @param status
	 * @throws IOException
	 */
	public void publishOnlineStatus(String user, String status) throws IOException {
		List<String> userList = registry.getRegisteredUsers();
		String userListJson = new Gson().toJson(userList);

		JsonObject responseJSON = new JsonObject();
		responseJSON.addProperty("id", "responseOnlineStatus");
		responseJSON.addProperty("response", status);
		responseJSON.addProperty("message", user);
               
                log.debug("publishing online status to clients: {}",responseJSON);


		for (UserSession userSession : registry.getUserSessions()) {
                        responseJSON.addProperty("myUsername",userSession.getName()); //include my online sessinID
			userSession.sendMessage(responseJSON);
		}
	}

	private void releasePipeline(UserSession user) {
		MediaPipeline pipeline = pipelines.remove(user.getSessionId());
		if (pipeline != null) {
			pipeline.release();
		}
	}

	private void play(final UserSession userSession, JsonObject jsonMessage) {
		String user = jsonMessage.get("user").getAsString();
		log.debug("Playing recorded call of user [{}]", user);

		JsonObject response = new JsonObject();
		response.addProperty("id", "playResponse");

		if (registry.getByName(user) != null && registry.getBySession(userSession.getSession()) != null) {
			final PlayMediaPipeline playMediaPipeline = new PlayMediaPipeline(Utils.kurentoClient(), user,
					userSession.getSession());

			String sdpOffer = jsonMessage.get("sdpOffer").getAsString();

			userSession.setPlayingWebRtcEndpoint(playMediaPipeline.getWebRtc());

			playMediaPipeline.getPlayer().addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
				@Override
				public void onEvent(EndOfStreamEvent arg0) {
					UserSession user = registry.getBySession(userSession.getSession());
					releasePipeline(user);
					playMediaPipeline.sendPlayEnd(userSession.getSession());
				}
			});

			playMediaPipeline.getWebRtc().addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
				@Override
				public void onEvent(OnIceCandidateEvent event) {
					JsonObject response = new JsonObject();
					response.addProperty("id", "iceCandidate");
					response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));

					try {
						synchronized (userSession) {
							userSession.sendMessage(response);
						}
					} catch (IOException e) {
						log.error(e.getMessage());
					}
				}
			});

			String sdpAnswer = playMediaPipeline.generateSdpAnswer(sdpOffer);

			response.addProperty("response", "accepted");
			response.addProperty("sdpAnswer", sdpAnswer);

			playMediaPipeline.play();
			pipelines.put(userSession.getSessionId(), playMediaPipeline.getPipeline());

			playMediaPipeline.getWebRtc().gatherCandidates();
		} else {
			response.addProperty("response", "rejected");
			response.addProperty("error", "No recording for user [" + user + "]. Please request a correct user!");
		}

		try {
			synchronized (userSession) {
				userSession.sendMessage(response);
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		}
	}

	private void handleErrorResponse(Exception throwable, Session session, String responseId) {
		try {
			stop(session,false);
		} catch (IOException ex) {
			log.error(ex.getLocalizedMessage(), ex);
		}
		log.debug(throwable.getMessage(), throwable);
		JsonObject response = new JsonObject();
		response.addProperty("id", responseId);
		response.addProperty("response", "rejected");
		response.addProperty("message", throwable.getMessage());
		try {
			session.getBasicRemote().sendText(response.toString());
		} catch (IOException ex) {
			log.error(ex.getLocalizedMessage(), ex);
		}
	}

	/**
	 * Sends the configuration to android client.
	 * 
	 * @param session
	 * @param jsonMessage
	 * @throws IOException
	 */
            private void appConfig(Session session, JsonObject jsonMessage) throws IOException {

                
                String turnUsername = System.getProperty("TURN_USERNAME");
                if(turnUsername==null || turnUsername.equals("")) turnUsername = "akashionata";
                
                String turnPassword = System.getProperty("TURN_PASSWORD");
                if(turnPassword==null || turnPassword.equals("")) turnPassword = "silkroad2015";
                
                String turnUrl = System.getProperty("TURN_URL");
                if(turnUrl==null || turnUrl.equals("")) turnUrl = "turn:5.9.154.226:3478";
                
                
                String stunUrl = System.getProperty("STUN_URL");
                if(stunUrl==null || stunUrl.equals("")) stunUrl = "stun:5.9.154.226:3478";
               
                boolean turnEnabled = true;
                boolean stunEnabled = true;
                String type = "";
                
                try{
                    if(jsonMessage.get("type")!=null) type = jsonMessage.get("type").getAsString(); 
                }catch(Exception ex){System.err.println("type cannot be read from json...");}
                
               String stun = "{"+
                            "\"username\":\"\"," +
                            "\"password\":\"\"," +
                            "\"urls\":[" +"\""+stunUrl+"?transport=udp\",\""+stunUrl+"?transport=tcp\"";
               
               String stun2 = ""; /*,\"stun:stun.l.google.com:19302\",";
                            stun2 += "\"stun:stun1.l.google.com:19302\",";
                            stun2 += "\"stun:stun2.l.google.com:19302\",";
                            stun2 += "\"stun:stun3.l.google.com:19302\",";
                            stun2 += "\"stun:stun4.l.google.com:19302\",";
                            stun2 += "\"stun:stun.ekiga.net\",";
                            stun2 += "\"stun:stun.ideasip.com\",";
                            stun2 += "\"stun:stun.schlund.de\",";
                            stun2 += "\"stun:stun.voiparound.com\",";
                            stun2 += "\"stun:stun.voipbuster.com\",";
                            stun2 += "\"stun:stun.voipstunt.com\",";
                            stun2 += "\"stun:stun.voxgratia.org\",";
                            stun2 += "\"stun:stun.services.mozilla.com\"";*/
                           
                            stun += stun2+"]}"; 
               
                String turn = "{" +
                             "\"username\":\""+turnUsername+"\"," +
                             "\"password\":\""+turnPassword+"\"," +
                             "\"urls\":[" 
                                          //  +"\""+turnUrl+"?transport=udp\"," 
                                          //  +"\""+turnUrl+"?transport=tcp\"" +
                                            +"\""+turnUrl+"\"" +
                                       "]}";
               
                          
                if(type!=null && type.equals("browser")){
                    turn =    "{\"urls\":[" 
                                         +"\""+turnUrl+"\"" +
                                           // +"\""+turnUrl+"?transport=udp\"," 
                                           // +"\""+turnUrl+"?transport=tcp\"" +
                                       "],\"username\":\""+turnUsername+"\",\"credential\":\""+turnPassword+"\"}";
                    stun =    "{\"urls\":[" 
                            +"\""
                            +stunUrl+"\""
                            //+stunUrl+"?transport=udp\",\""
                            //+stunUrl+"?transport=tcp\""
                            +stun2+"]}";
                }
                
                 String iceConfig = "[";
                      if(stunEnabled) iceConfig+=stun;
                      if(stunEnabled && turnEnabled) iceConfig+=",";
                      if(turnEnabled) iceConfig+=turn;
                      iceConfig+= "]";
                
		String responseJSON = "{" + "\"params\" : {" 
				+ "\"pc_config\": {\"iceServers\": "+ iceConfig + "}" + //, \"iceTransportPolicy\": \"relay\"
				"}," + "\"result\": \"SUCCESS\"" + "}";
                
                log.debug(responseJSON);
		session.getBasicRemote().sendText(responseJSON);

		log.info("send app config to: {}", session.getId());
	}

	/**
	 * 
         * Registers a user with the given session on the server.
	 * 
	 * @param session
	 * @param jsonMessage
	 * @return true, if registration was successful. False, if user could not be
	 *         registered.
	 * @throws IOException
	 */
	private boolean register(Session session, JsonObject jsonMessage) throws IOException {

            
             
                        
		String name = jsonMessage.getAsJsonPrimitive("name").getAsString();
                if(registry.exists(name)){
                    checkIfSessionIsAlive(registry.getByName(name));
                }
                    
		log.debug("register called: {}", name);

		boolean registered = false;
                
		UserSession newUser = new UserSession(session, name);
		String response = "accepted";
		
                String message = "";
		if (name.isEmpty()) {
			response = "rejected";
			message = "empty user name";
                
		//} else if (registry.exists(name)) { if this username already existed in a different session check if it's still alive (send message) if error kill it
           
		//	response = "skipped";
		//	message = "user " + name + " already registered";
		} else {
			registry.register(newUser);
			registered = true;
		}

		JsonObject responseJSON = new JsonObject();
		responseJSON.addProperty("id", "registerResponse");
		responseJSON.addProperty("response", response);
		responseJSON.addProperty("message", message);
                responseJSON.addProperty("myUsername",name);
		newUser.sendMessage(responseJSON);

		log.debug("Sent response: {}", responseJSON);
		return registered;
	}
        
        private void checkIfSessionIsAlive(UserSession userSession){
            
                 JsonObject responseJSON = new JsonObject();
                 
                 responseJSON.addProperty("id", "ping");
                           // responseJSON.addProperty("response", userListJson);
                          //  responseJSON.addProperty("message", "");
                 log.debug("sending ping: {}",responseJSON.toString());
            try {
                userSession.sendMessage(responseJSON);
            } catch (IOException ex) {
               registry.removeBySession(userSession.getSession());
            }
            
        }
	/**
	 * Updates the list of registered users on all clients.
	 * 
	 * @throws IOException
	 */
	private void sendRegisteredUsers() throws IOException {
		List<String> userList = registry.getRegisteredUsers();
               
		for (UserSession userSession : registry.getUserSessions()) {
                       if(userSession.getSession().isOpen()){
                           
                            ArrayList thisUserList = new ArrayList();
                            thisUserList.addAll(userList);
                            thisUserList.remove(userSession.getName()); //don't send own username
                            String userListJson = new Gson().toJson(thisUserList);

                            JsonObject responseJSON = new JsonObject();
                            responseJSON.addProperty("id", "registeredUsers");
                            responseJSON.addProperty("response", userListJson);
                            responseJSON.addProperty("message", "");
                            log.error("sending userlist: {}",responseJSON.toString());
                            userSession.sendMessage(responseJSON);
                       }else{
                           log.info("removing session id from registry because it's not open {}", userSession.getSession());
                           registry.removeBySession(userSession.getSession());
                       }
		}
	}

	private void call(UserSession caller, JsonObject jsonMessage) throws IOException {
                
		String to = jsonMessage.get("to").getAsString();
		String from = jsonMessage.get("from").getAsString();
                                
                boolean isScreensharing =  jsonMessage.has("screensharing");
		// System.out.println("call from :" + from + " to:" + to);
		log.info("call from [{}] to [{}]", from, to, isScreensharing);

		JsonObject response = new JsonObject();

		UserSession callee = registry.getByName(to);

		if (callee != null && !callee.isBusy()) {
			caller.setSdpOffer(jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
			caller.setCallingTo(to);

			response.addProperty("id", "incomingCall");
                        if(isScreensharing) response.addProperty("screensharing", "true");  
			response.addProperty("from", from);

			log.debug("Sending response [{}] to callee [{}]", response.toString(), callee.getName());

			callee.sendMessage(response);
			callee.setCallingFrom(from);
		} 
                else if(callee.isBusy()){
                    
                        log.error("Callee [{}] does not exist! Rejecting call.", to);

			response.addProperty("id", "callResponse");
                        response.addProperty("response","rejected");
			response.addProperty("message", "rejected: user '" + to + "' is busy!");

			caller.sendMessage(response);
                    
                }
                else {
			log.error("Callee [{}] does not exist! Rejecting call.", to);

			response.addProperty("id", "callResponse");
                        response.addProperty("response","rejected");
			response.addProperty("message", "rejected: user '" + to + "' is not registered");

			caller.sendMessage(response);
		}
	}

	private void incomingCallResponse(final UserSession callee, JsonObject jsonMessage) throws IOException {
		String callResponse = jsonMessage.get("callResponse").getAsString();
		String from = jsonMessage.get("from").getAsString();
		final UserSession caller = registry.getByName(from);
		String to = caller.getCallingTo();

		if ("accept".equals(callResponse)) {
			log.info("Accepted call from [{}] to [{}]", from, to);

			CallMediaPipeline pipeline = null;
			try {
				pipeline = new CallMediaPipeline(Utils.kurentoClient(), from, to);
				pipelines.put(caller.getSessionId(), pipeline.getPipeline());
				pipelines.put(callee.getSessionId(), pipeline.getPipeline());
				log.debug("created both pipelines...");

				// give the callee his webRtcEp from the pipeline
				callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());

				pipeline.getCalleeWebRtcEp().addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
					@Override
					public void onEvent(OnIceCandidateEvent event) {
						JsonObject response = new JsonObject();
						response.addProperty("id", "iceCandidate");
						response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
						try {
							synchronized (callee.getSession()) {
                                                              log.debug(response.toString());
								callee.getSession().getBasicRemote().sendText(response.toString());
							}
						} catch (IOException e) {
							log.error(e.getMessage(), e);
						}
					}
				});

                                
                                //add filter 
                                      // Media logic

                                
                                
				caller.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());

				pipeline.getCallerWebRtcEp().addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {

					@Override
					public void onEvent(OnIceCandidateEvent event) {
						JsonObject response = new JsonObject();
						response.addProperty("id", "iceCandidate");
						response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
						try {
							synchronized (caller.getSession()) {
                                                            log.debug(response.toString());
                                                            caller.getSession().getBasicRemote().sendText(response.toString());
							}
						} catch (IOException e) {
							log.error(e.getMessage(), e);
						}
					}
				});
				log.debug("created both webrtcendpoints...");

				log.debug("preparing sending startCommunication to called person...");

				String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
				String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);

				log.debug("we have callee offer and answer as it seems");

				JsonObject startCommunication = new JsonObject();
				startCommunication.addProperty("id", "startCommunication");
				startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

				synchronized (callee) {
					log.debug("sending startCommunication message to callee");
					callee.sendMessage(startCommunication);
				}

				pipeline.getCalleeWebRtcEp().gatherCandidates();

				String callerSdpOffer = registry.getByName(from).getSdpOffer();
				String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
				JsonObject response = new JsonObject();
				response.addProperty("id", "callResponse");
				response.addProperty("response", "accepted");
				response.addProperty("sdpAnswer", callerSdpAnswer);

				synchronized (caller) {
					log.debug("sending callResponse message to caller");
					caller.sendMessage(response);
				}

				pipeline.getCallerWebRtcEp().gatherCandidates();

				pipeline.record();
                                
   /*     MediaPipeline mediaPipeline = pipeline.getPipeline();
        FaceOverlayFilter faceOverlayFilterCaller = new FaceOverlayFilter.Builder(mediaPipeline).build();
        FaceOverlayFilter faceOverlayFilterCallee = new FaceOverlayFilter.Builder(mediaPipeline).build();

     // String appServerUrl = System.getProperty("app.server.url", "https://localhost/jWebrtc/");
        faceOverlayFilterCaller.setOverlayedImage("https://192.168.178.22/jWebrtc/img/santa-overlay.png", 0F, 0F, 1.0F, 1.0F);
        faceOverlayFilterCallee.setOverlayedImage("https://192.168.178.22/jWebrtc/img/santa-overlay.png", 0F, 0F, 1.0F, 1.0F);
      //faceOverlayFilter2.setOverlayedImage(appServerUrl + "/img/santa-overlay.png", -0.35F, -1.2F, 1.6F, 1.6F);

        pipeline.getCallerWebRtcEp().connect(faceOverlayFilterCaller);
        faceOverlayFilterCaller.connect(pipeline.getCalleeWebRtcEp());
           
        pipeline.getCalleeWebRtcEp().connect(faceOverlayFilterCallee);
        faceOverlayFilterCallee.connect(pipeline.getCallerWebRtcEp());
     // log.error("added overlay");
       
     // faceOverlayFilter2.connect(pipeline.getCalleeWebRtcEp());
        log.error("overlay connected");*/

			} catch (Throwable t) {

				log.error("Rejecting call! Reason: {}", t.getMessage());

				if (pipeline != null) {
					pipeline.release();
				}

				pipelines.remove(caller.getSessionId());
				pipelines.remove(callee.getSessionId());

				JsonObject response = new JsonObject();
				response.addProperty("id", "callResponse");
                                response.addProperty("message", t.getMessage());
				response.addProperty("response", "rejected");
				caller.sendMessage(response);

				response = new JsonObject();
				response.addProperty("id", "stopCommunication");
				callee.sendMessage(response);
			}

		} else { // "reject"
			JsonObject response = new JsonObject();
			response.addProperty("id", "callResponse");
			response.addProperty("response", "rejected");
			caller.sendMessage(response);
		}
	}
        
        public void killUserSession(Session session) throws IOException{
            String sessionId = session.getId();
            log.debug("Killing usersession from of websocket id [{}]", sessionId);
            registry.removeBySession(session);
            
            sendRegisteredUsers(); 
        }
        
	public void stop(Session session, boolean sendCallback) throws IOException {

		String sessionId = session.getId();
                log.debug("trying to find session id: {} in piplines:\n{}",sessionId,pipelines.keySet().toString());
                
		
                // Both users can stop the communication. A 'stopCommunication'
                // message will be sent to the other peer.
                UserSession stopperUser = registry.getBySession(session);
                log.info("stopperUser: "+stopperUser.getName());

                if (stopperUser != null) {

                    UserSession stoppedUserFrom = (stopperUser.getCallingFrom() != null) ? registry.getByName(stopperUser.getCallingFrom()) : null;

                    UserSession stoppedUserTo = (stopperUser.getCallingTo() != null )? registry.getByName(stopperUser.getCallingTo()) : null;
                    UserSession stopUser = null;

                    if(stoppedUserFrom !=null && stoppedUserFrom.getSession()!=null && !stoppedUserFrom.getSession().getId().equals(session.getId())){
                        log.debug("die id des stoppenden ist NICHT! die des anrufenden"); //wenn der angerufene auflegt

                        stopUser = stoppedUserFrom;
                        JsonObject message = new JsonObject();
                        message.addProperty("id", "stopCommunication");
                        if(sendCallback==true) message.addProperty("callback", "true");
                        stopUser.sendMessage(message);
                      //  if(!sendCallback)
                         stopUser.clearWebRtcSessions();                       
                    }      
                    else if(stoppedUserTo!=null && stoppedUserTo.getSession()!=null){
                        log.debug("die id des stoppenden IST! die des anrufenden");
                        //wenn der anrufer auflegt. (wird anschließend, die pipeline des anrufenden gesucht und exisitert nicht mehr) 
                       stopUser = stoppedUserTo;
                       JsonObject message = new JsonObject();
                       message.addProperty("id", "stopCommunication");
                       if(sendCallback==true) message.addProperty("callback", "true");
                       stopUser.sendMessage(message);
                     //  if(!sendCallback)
                        stopUser.clearWebRtcSessions();                  
                   }
                   

                    if (pipelines.containsKey(sessionId)) {
                        log.debug("Stopping media connection of websocket id [{}]", sessionId);
                        log.debug("send stop to stoppedUserFrom:",stopUser.getName());
                        MediaPipeline pipeline1 = pipelines.remove(sessionId);
                        pipeline1.release();

                        MediaPipeline pipeline2 = pipelines.remove(stopUser.getSession().getId());
                        pipeline2.release();
                    }
                    //if(!sendCallback) 
                        stopperUser.clearWebRtcSessions();
                    log.error("Stopped", sessionId);
                    sendRegisteredUsers(); 
                }
		//}
                //else{ //piplines not yet have been created - but a user tried to call another and the other hangs up instead of answers the call
            
               // }
	}

}