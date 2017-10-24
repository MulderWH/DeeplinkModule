// This file was generated by Mendix Modeler.
//
// WARNING: Only the following code will be retained when actions are regenerated:
// - the import list
// - the code between BEGIN USER CODE and END USER CODE
// - the code between BEGIN EXTRA CODE and END EXTRA CODE
// Other code you write will be lost the next time you deploy the project.
// Special characters, e.g., é, ö, à, etc. are supported in comments.

package deeplink.actions;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.logging.ILogNode;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.ISession;
import com.mendix.webui.CustomJavaAction;
import deeplink.proxies.DeepLink;
import deeplink.proxies.PendingLink;

/**
 * Starts the deeplink modle. Initializes the "/link/" request handler.
 * 
 * Returns true (always)
 */
public class StartDeeplinkJava extends CustomJavaAction<Boolean>
{
	public StartDeeplinkJava(IContext context)
	{
		super(context);
	}

	@Override
	public Boolean executeAction() throws Exception
	{
		// BEGIN USER CODE
		Core.addRequestHandler(deeplink.proxies.constants.Constants.getRequestHandlerName()+ "/", new DeepLinkHandler());
		return true;
		// END USER CODE
	}

	/**
	 * Returns a string representation of this action
	 */
	@Override
	public String toString()
	{
		return "StartDeeplinkJava";
	}

	// BEGIN EXTRA CODE
	/**
	 * This class does all the complex work of the deep link module. 
	 * It takes care of session management, authentication and link resolving. 
	 * Deeplinking performs the following step
	 * 1) find link configuration
	 * 2) find a session based on cookies, or login POST data
	 * 3) if 2 fails, set up a guest session if allowed, or serve the login page
	 * 4) store the session data
	 * 5) find the link subject, using the restored session (this applies the proper security)
	 * 6) create a pending link object
	 * 7) redirect to index.html to start the client. 
	 * 8) the execute deeplink will be called after loading the app, and takes care of the rest
	 * 
	 *  You might wonder why session management is taken care of by the deeplink module. Well,
	 *  1) we do not want to burden the server or browser by loading the client for invalid or not
	 *  accessible objects
	 *  2) cookies or request header are not accessible in java actions, 
	 *  and we need to uniquely identify the current user before redirecting to the client app, 
	 *  which should pick the information up after starting
	 */
	class DeepLinkHandler extends RequestHandler {
		private static final String DEFAULTLOGINTEXT = "Sign in";
		private static final String ERRORLOGINTEXT = "The username or password you entered is incorrect.";
		private static final String XAS_ID = "XASID";
		private static final String HTML_CONTENT = "text/html";
		private static final String ENCODING = "UTF-8";
		private static final String ARGUSER = "username";
		private static final String ARGPASS = "password";
		private String loginLocation = emptyStringToNull((String) Core.getConfiguration().getConstantValue("DeepLink.LoginLocation"));
		private static final String	INDEX_CONSTANT	= "DeepLink.IndexPage";
		private static final String	DEFAULT_INDEX_LOCATION	= "index.html";
		
		@Override
		public void processRequest(IMxRuntimeRequest request,
				IMxRuntimeResponse response, String arg2) throws Exception 
		{
		    /** BJHL - CC0000000100277
             * Office (esp. Word) opens links by fetching the URL using its own cookies, following 
             * any redirects and opening the resulting location in a browser. The browser will then
             * send its cookies, which ultimately results in a session mismatch between word and
             * the browser, causing the pending deeplink to be created for the wrong session. This
             * is a know problem with word: 
             *   https://stackoverflow.com/questions/1421608/how-does-ms-word-open-a-hyperlink
             * To "fix" this, ignore any requests where the user-agent contains "office". Sending
             * a 200 OK tells word the end of the redirect is reached, and it will happily open the 
             * original URL in your default browser... 
             */
            if (request.getHeader("User-Agent").toLowerCase().contains("office")) {
                response.setStatus(IMxRuntimeResponse.OK);
                return;
            }
            
			try {
				String[] args = request.getResourcePath().split("/");
				for (int j1 = 0; j1 < args.length; j1++) //args will be used in queries, so escape
					args[j1] = StringEscapeUtils.escapeXml(args[j1]);
				
				if (args.length < 3) {
					StartDeeplinkJava.logger.warn("Received invalid number of arguments: " + args.length);
					serve404(request, response, null);
					return;
				}
				
				ISession session = this.getSessionFromRequest(request);
				
				/** RSA - Ticket #102924
				*	User wasn't able to login after logging out and using the deeplink url(serving a
				*	login page) again. After logout the user kept the session, but was anonymous.
				*	When having a session, the 'performLogin' method was not called, resulting in 
				*	a new login page for the user instead of being logged in.
				*/
				if (request.getParameter(ARGUSER)!= null && request.getParameter(ARGPASS) != null) {
                	if ( session == null || (session!=null && session.getUser().isAnonymous()) ) {
                    	if ( session == null ) 
                    		StartDeeplinkJava.logger.debug("No session found for deeplink: " + request.getResourcePath() + ", attempting login.");
                    	else	
                    		StartDeeplinkJava.logger.debug("Using session from request: '" + session.getId().toString() + "' for deeplink: " + request.getResourcePath());
                			
                    	session = performLogin(request, response);
                	}
                	
                    if (session != null) {
                        serveDeeplink(args, request, response, session);
                    } else { // user/pw combo is invalid, server login page (again)
                        serveLogin(request, response, ERRORLOGINTEXT);
                    }
                } else { // try to serve as guest link
                    StartDeeplinkJava.logger.debug("No session found for deeplink: " + request.getResourcePath() + ", attempting to serve link as guest.");
                    serveDeeplink(args, request, response, session);
                }
			}
			catch (Exception e)
			{
				response.setStatus(500); //internal server error
				StartDeeplinkJava.logger.error("Error while serving deeplink: ", e);
			}				
		}

		private void serveDeeplink(String[] args, IMxRuntimeRequest request, IMxRuntimeResponse response, ISession existingsession) throws Exception {
			IContext context = Core.createSystemContext();
			IMendixObject deeplinkObj = query(context, DeepLink.getType(), DeepLink.MemberNames.Name, args[2]);
			ISession session = existingsession;
			
			if (deeplinkObj == null) {
				StartDeeplinkJava.logger.warn("Deeplink with name '" + args[2] + "' not found. ");
				serve404(request, response, session == null ? null : session.getUser().getName());
				return;
			}
			
			DeepLink deeplink = DeepLink.initialize(context, deeplinkObj);
			
			if (session == null) 
			{
				if (deeplink.getAllowGuests().booleanValue())
					session = createGuestSession(response);
				else //session is required
				{
					serveLogin(request, response, DEFAULTLOGINTEXT);
					return;
				}
			}
			else if (!deeplink.getAllowGuests().booleanValue() && session.getUser().isAnonymous()) //guest session, which is not allowed
			{
				serveLogin(request, response, DEFAULTLOGINTEXT);
				return;
			}

			String userAgent = request.getHeader("user-agent");
			if (userAgent != null && session != null) {
				session.setUserAgent(userAgent);
			}
			
			//switch to the users context
			context = session.createContext(); 
			String user = session.getUser().getName();

			//we have a valid session, further security is enforced by the application
			
			//first, remove old pending links
			List<IMendixObject> pendinglinks = Core.retrieveXPathQueryEscaped(context, "//%s[%s='%s' and %s='%s']",
					PendingLink.getType(), 
					PendingLink.MemberNames.PendingLink_DeepLink.toString(), String.valueOf(deeplink.getMendixObject().getId().toLong()),
					PendingLink.MemberNames.User.toString(), user 
			);
			
			Core.delete(context, pendinglinks);
			
			//find the object, we search the object before serving the deeplink, to avoid loading the client for
			//incorrect links
			Long theobject = 0L;
			if (!deeplink.getUseStringArgument() && deeplink.getObjectType() != null && !deeplink.getObjectType().isEmpty()) {
				String argument = args.length < 4 ? "" : (args[3] == null ? "" : args[3]); 
				IMendixObject arg = null;
				
				if (deeplink.getObjectAttribute() == null || deeplink.getObjectAttribute().isEmpty()) 
					arg = Core.retrieveId(context, Core.createMendixIdentifier(argument));
				else //use attr
					arg = query(context, deeplink.getObjectType(), deeplink.getObjectAttribute(), argument); //argument is already escaped
				
				if (arg == null) 
				{
					StartDeeplinkJava.logger.warn(String.format("While serving deeplink '%s', the object %s.%s for value '%s' was not found", deeplink.getName(), deeplink.getObjectType(), deeplink.getObjectAttribute(), argument));
					serve404(request, response, user);
					return;
				}
				
				theobject = arg.getId().toLong();
			}
			
			//then create a new pendinglink
			PendingLink link = PendingLink.initialize(context, Core.instantiate(context, PendingLink.entityName));
			link.setPendingLink_DeepLink(deeplink);
			link.setUser(session.getUser().getName());
			link.setArgument(theobject);
			link.setSessionId(session.getId().toString());
			
			//take the remainder of the path, if getusestringargument is true. Escape that? -> No, responsibility of the microflow
			if (deeplink.getUseStringArgument()) {
				String path = request.getResourcePath();
				
				// calculate cutoff point to get to String parameter
				int pos = path.indexOf("/" + deeplink.getName()) + deeplink.getName().length() + 2;
				if (pos < path.length() && pos != -1)
				    path = path.substring(pos);
				else 
				    path = "";
				
				// set String argument
				link.setStringArgument(path);
				
				// if GET parameters should be included, add them 
                if (deeplink.getIncludeGetParameters()) {
                    String qs = request.getHttpServletRequest().getQueryString();
                    if (qs != null && !qs.isEmpty()) {
                        // if we want to split GET parameters, set the query string only
                        if (deeplink.getSeparateGetParameters()) {
                            link.setStringArgument(qs);
                        } else {
                            link.setStringArgument(path + "?" + qs);
                        }
                    }
                }
			}
			
			Core.commit(context, link.getMendixObject());
			
			//finally, redirect
			response.setStatus(IMxRuntimeResponse.SEE_OTHER);
			
			String location = emptyStringToNull(deeplink.getIndexPage());
			if (location == null)
				location = emptyStringToNull((String) Core.getConfiguration().getConstantValue(INDEX_CONSTANT));
			if (location == null)
				location = DEFAULT_INDEX_LOCATION;
			
			response.addHeader("location", getRelPath(request) + location);
		}

		private ISession createGuestSession(IMxRuntimeResponse response) throws Exception {
			if (!Core.getConfiguration().getEnableGuestLogin())
				throw new Exception("Guest login is not enabled");
			
			ISession session = Core.initializeGuestSession();
			setCookies(response, session);
			
			return session;
		}

		private void setCookies(IMxRuntimeResponse response, ISession session) {
			response.addCookie(Core.getConfiguration().getSessionIdCookieName(), session.getId().toString(),  "/", "", -1, true);
			response.addCookie(XAS_ID, "0."+Core.getXASId(),"/", "", -1, true);			 
		}

		private ISession performLogin(IMxRuntimeRequest request,
				IMxRuntimeResponse response) throws Exception {
			String username = request.getParameter(ARGUSER);
			String password = request.getParameter(ARGPASS);
						
			try {
				ISession session =  Core.login(username, password);
				StartDeeplinkJava.logger.info("Login OK: user '" + username + "'");
				setCookies(response, session);
				return session;				
			}
			catch (Exception e) {
				StartDeeplinkJava.logger.warn("Login failed for '"  + username + "' : " + e.getMessage());
				return null;
			}
		}

		private String getRelPath(IMxRuntimeRequest request) {
			String res = "";
			int length = request.getResourcePath().split("/").length +
				(request.getResourcePath().endsWith("/") ? 0 : -1);
			for(int i1 = 0; i1 < length; i1++)
				res+= "../";
			return res;
		}
		
		private void serveLogin(IMxRuntimeRequest request,
				IMxRuntimeResponse response, String result) throws IOException {
			String url = request.getResourcePath();
			String qs = request.getHttpServletRequest().getQueryString();
			if (url.startsWith("/")) {
				url = url.substring(1);
			}
			if (qs != null && !qs.equals("")) {
			    url = url + "?" + URLEncoder.encode(qs, "UTF-8");
			}
				
			//use alternative login?
			if (this.loginLocation != null) {
				StartDeeplinkJava.logger.debug("Redirecting to login location: " + this.loginLocation + " " + url );
				response.setStatus(IMxRuntimeResponse.SEE_OTHER);
				response.addHeader("location", this.loginLocation + url);
				return;
			}
			
			Map<String, String> args = new HashMap<String, String>();
			args.put("url", url);
			args.put("result", result);
			args.put("relpath", getRelPath(request));
			
			renderTemplate("login", args, response);
			response.setStatus(IMxRuntimeResponse.OK);
		}

		private void serve404(IMxRuntimeRequest request, IMxRuntimeResponse response, String user) throws IOException {
		    String qs = request.getHttpServletRequest().getQueryString();
            		    
		    Map<String, String> args = new HashMap<String, String>();
			args.put("url",     request.getResourcePath() + ((qs != null && !qs.equals("")) ? "?" + qs : ""));
			args.put("user",    user == null ? "" : " (" + user + ")");
			args.put("relpath", getRelPath(request));
			
			renderTemplate("404", args, response);
			response.setStatus(IMxRuntimeResponse.NOT_FOUND);
		}
		
		//Template methods borrowed from MxID
		public void renderTemplate(String template, Map<String, String> params, IMxRuntimeResponse response) throws IOException
		{
			response.setContentType(HTML_CONTENT);
			response.setCharacterEncoding(ENCODING);
			response.getWriter().append(renderTemplate(template, params));
		}

		public String renderTemplate(String template, Map<String, String> params) throws IOException
		{
			String line = FileUtils.readFileToString(new File(Core.getConfiguration().getResourcesPath() + File.separator + "deeplink" + File.separator + template + ".html"));
			if (params != null)
				for(String key : params.keySet())
					if (params.get(key) != null)
						line = line.replaceAll("\\{"+key.toUpperCase()+"\\}", Matcher.quoteReplacement(StringEscapeUtils.escapeHtml4(params.get(key))));
			return line;
		}	
	}
	
	//Deeplink commons
	protected static ILogNode logger = Core.getLogger("DeepLink");
	
	protected static IMendixObject query(IContext context, String type, Object field /* Enum value */, String value) 
	{
		try {
			List<IMendixObject> result = Core.retrieveXPathQueryEscaped(context, "//%s[%s='%s']", 
					type, field.toString(), value);
			return result.size() > 0 ? result.get(0) : null;
		} catch (CoreException e) {
			StartDeeplinkJava.logger.error("Error while executing query: ", e);
			return null;
		}
	}
	
	static String emptyStringToNull(String value) {
		if (value == null)
			return null;
		
		if (value.trim().isEmpty())
			return null;
		if ("\"\"".equals(value) || "''".equals(value))
			return null;
		return value;
	}
	// END EXTRA CODE
}
