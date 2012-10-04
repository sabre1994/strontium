package srl.distributed.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Writer;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.map.ObjectMapper;

import srl.distributed.Serialize;
import srl.distributed.client.exceptions.ClientException;
import srl.distributed.messages.ClientErrorResponse;
import srl.distributed.messages.Request;
import srl.distributed.messages.Response;



public class Client {
	private static final int DEFAULT_TIMEOUT = 10000;
	private HttpClient httpClient = new DefaultHttpClient();

	private CookieStore cookieStore = new BasicCookieStore();
	private HttpContext localContext = new BasicHttpContext();
	private Writer messageLog = null;
	private boolean messageTimingEnabled = false;
	private Timing messageTiming = new Timing();

	private URL serverAddress;
	
	private boolean connected;
	
	private Object lock = new Object();
	
	public Client(URL address){
		serverAddress = address;
		localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
		httpClient = getNewHttpClient();
		setTimeout(DEFAULT_TIMEOUT);
	}
	public void loadCookies(File file){
		try {
			FileInputStream fis = new FileInputStream(file);
			ObjectInputStream ois = new ObjectInputStream(fis);
			cookieStore = (CookieStore)ois.readObject();
		} catch (Exception e) {
			e.printStackTrace();
			cookieStore = new BasicCookieStore();
		} 

		localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
	}
	public void storeCookies(File file){
		try{
			FileOutputStream fos = new FileOutputStream(file);
			ObjectOutputStream oos = new ObjectOutputStream(fos);
			oos.writeObject(cookieStore);
		}
		catch(Exception e){
			e.printStackTrace();
		}
	}
	public void setMessageLoggingEnabled(File file) throws IOException{
		messageLog = new FileWriter(file);
	}
	public void setMessageLoggingEnabled(Writer writer){
		messageLog = writer;
	}
	public void setMessageTimingEnabled(boolean enabled){
		messageTimingEnabled = enabled;
	}
	public Timing getMessageTiming(){
		return messageTiming;
	}
	public boolean connected(){
		synchronized(lock){
			return connected;
		}
	}
	public boolean testConnection(){
		try{
			return sendRequest(new srl.distributed.messages.PingRequest(System.currentTimeMillis())).getSuccess();
		}
		catch(Exception e){
			e.printStackTrace();
		}
		return false;
	}
	private void setConnected(boolean connected){
		synchronized(lock){
			this.connected = connected;
		}
	}
	private void setTimeout(int timeout){
		HttpParams params = httpClient.getParams();
		HttpConnectionParams.setConnectionTimeout(params, timeout);
		HttpConnectionParams.setSoTimeout(params, timeout);
	}
	public synchronized Response sendRequest(Request request, int timeout){
		setTimeout(timeout);
		Response response = sendRequest(request);
		setTimeout(DEFAULT_TIMEOUT);
		return response;
	}
	
	public synchronized Response sendRequest(Request request){
		setConnected(false);
		HttpPost post = new HttpPost(serverAddress.toString());
		HttpEntity responseEntity = null;
		Response message = null;
		ObjectMapper mapper = Serialize.buildMapper();
		
		messageTiming.reset();
		
		try {
			if(messageTimingEnabled)
				messageTiming.startSerialization();
			
			String requestVal = mapper.writeValueAsString(request);
			
			if(messageTimingEnabled)
				messageTiming.endSerialization();
			
			if(messageLog!=null){
				messageLog.append("//REQUEST\n");
				messageLog.append(requestVal+"\n");
				messageLog.flush();
			}
			if(messageTimingEnabled)
				messageTiming.startTransmission();
			
			StringEntity entity = new StringEntity(requestVal);
			post.setEntity(entity);
			entity.setContentType("application/json");
			HttpResponse response;
			synchronized(httpClient){
				response = httpClient.execute(post,localContext);
			}
			
			if(messageTimingEnabled)
				messageTiming.endTransmission();
			if(messageTimingEnabled)
				messageTiming.startDeserialization();
			responseEntity = response.getEntity();
			InputStream inStream = responseEntity.getContent();
			
			if(responseEntity.getContentType() != null){
				if(messageLog==null)
					message = mapper.readValue(inStream,Response.class);
				else{
					String entityVal = EntityUtils.toString(responseEntity);
					messageLog.append("//RESPONSE\n");
					messageLog.append(entityVal+"\n");
					messageLog.flush();
					message = mapper.readValue(entityVal, Response.class);
				}
				inStream.close();
				setConnected(true);
			}
			else{
				throw new ClientException("Able to connect but response is not from Server. Incorrect URL?");
			}
		} 
		catch (ClientException e) {
			e.printStackTrace();
			message = new ClientErrorResponse(e);
		}
		catch(Exception e){
			e.printStackTrace();
			message = new ClientErrorResponse(e);
		}
		finally{
			try {
				if(responseEntity!=null)
					responseEntity.consumeContent();
			} catch (IOException e) {
				message = new ClientErrorResponse(e);
			}
		}
		if(messageTimingEnabled)
			messageTiming.endDeserialization();
		return message;
	}
	private HttpClient getNewHttpClient() {
	    try {
	        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        trustStore.load(null, null);

	        SSLSocketFactory sf = new MySSLSocketFactory(trustStore);
	        sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

	        HttpParams params = new BasicHttpParams();
	        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
	        HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

	        SchemeRegistry registry = new SchemeRegistry();
	        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
	        registry.register(new Scheme("https", sf, 443));

	        ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

	        return new DefaultHttpClient(ccm, params);
	    } catch (Exception e) {
	        return new DefaultHttpClient();
	    }
	}
	
	public static class Timing {
		long serializationStart,serializationEnd;
		long transmissionStart,transmissionEnd;
		long deserializationStart, deserializationEnd;
		
		public void reset(){
			serializationStart=serializationEnd=transmissionStart=transmissionEnd=deserializationStart=deserializationEnd=-1L;
		}
		
		public void startSerialization(){
			serializationStart = System.currentTimeMillis();
		}
		public void endSerialization(){
			serializationEnd = System.currentTimeMillis();
		}
		public long getSerializationTime(){
			if(serializationStart != -1L && serializationEnd != -1L)
				return serializationEnd - serializationStart;
			return -1L;
		}
		public void startTransmission(){
			transmissionStart = System.currentTimeMillis();
		}
		public void endTransmission(){
			transmissionEnd = System.currentTimeMillis();
		}
		public long getTransmissionTime(){
			if(transmissionStart != -1L && transmissionEnd != -1L)
				return transmissionEnd - transmissionStart;
			return -1L;
		}
		public void startDeserialization(){
			deserializationStart = System.currentTimeMillis();
		}
		public void endDeserialization(){
			deserializationEnd = System.currentTimeMillis();
		}
		public long getDeserializationTime(){
			if(deserializationStart != -1L && deserializationEnd != -1L)
				return deserializationEnd - deserializationStart;
			return -1L;
		}
		
	}
}