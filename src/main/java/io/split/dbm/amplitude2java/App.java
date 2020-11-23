package io.split.dbm.amplitude2java;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args ) throws Exception
    {
        new App().execute();
    }

	private void execute() throws Exception {
		long begin = System.currentTimeMillis();
		
		String configFile = readFile("amplitude2java.config");
		JSONObject configObj = new JSONObject(configFile);
		String AMPLITUDE_API_TOKEN = configObj.getString("amplitude.api.key");
		String AMPLITUDE_API_SECRET = configObj.getString("amplitude.api.secret");
		String SPLIT_API_TOKEN = configObj.getString("split.api.key");
		String SPLIT_TRAFFIC_TYPE = configObj.getString("trafficType");
		String SPLIT_ENVIRONMENT = configObj.getString("environment");
		String AMPLITUDE_PREFIX = configObj.getString("eventPrefix");
		int BATCH_SIZE = configObj.getInt("batchSize");
		JSONArray keysArray = configObj.getJSONArray("string.property.keys");
		String[] STRING_PROPERTY_KEYS = new String[keysArray.length()];
		for(int i = 0; i < keysArray.length(); i++) {
			STRING_PROPERTY_KEYS[i] = keysArray.getString(i);
		}
		String USER_ID = configObj.getString("key");

		Instant nowUtc = Instant.now();
		Instant hourAgoUtc = nowUtc.minus(1, ChronoUnit.HOURS);
		Date now = Date.from(nowUtc);
		Date hourAgo = Date.from(hourAgoUtc);
		
		// uncomment to set timezone to UTC
		//TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HH");
		SimpleDateFormat serverFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		SimpleDateFormat serverFormat2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		
		System.out.println("INFO - start at " + serverFormat2.format(new Date(begin)));
		
		String end = format.format(now);
		String start = format.format(hourAgo);
		
		CredentialsProvider provider = new BasicCredentialsProvider();
		UsernamePasswordCredentials credentials
		 = new UsernamePasswordCredentials(AMPLITUDE_API_TOKEN, AMPLITUDE_API_SECRET);
		provider.setCredentials(AuthScope.ANY, credentials);
		 
		HttpClient client = HttpClientBuilder.create()
		  .setDefaultCredentialsProvider(provider)
		  .build();
		 
		String uri = "https://amplitude.com/api/2/export?start=" + start + "&end=" + end;
		System.out.println("INFO - GET " + uri);
		HttpResponse response = client.execute(new HttpGet(uri));
		int statusCode = response.getStatusLine().getStatusCode();
		System.out.println("INFO - status code: " + statusCode);
		
		if(statusCode >= 200 && statusCode < 300) {			
			// Handle the wrapper ZIP
			BufferedInputStream bis = new BufferedInputStream(response.getEntity().getContent());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(bis, baos);
			
			ZipArchiveInputStream zis = new ZipArchiveInputStream(
					new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
					"UTF-8", false, true);
			ZipArchiveEntry entry = null;
			while ((entry = zis.getNextZipEntry()) != null) {
				// Handle the Gzipd JSON
				System.out.println("INFO - " + entry.getName());
				ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
				IOUtils.copy(zis, zipBaos);
				GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(zipBaos.toByteArray()));
				ByteArrayOutputStream jsonBaos = new ByteArrayOutputStream();
				IOUtils.copy(gzis, jsonBaos);
				BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(jsonBaos.toByteArray())));
				String line = null;
				JSONArray events = new JSONArray();
				while((line = reader.readLine()) != null) {
					JSONObject o = new JSONObject(line);

					JSONObject event = new JSONObject();
					event.put("trafficTypeName", SPLIT_TRAFFIC_TYPE);
					event.put("environmentTypeName", SPLIT_ENVIRONMENT);
					
					String user_id = "";
					if(o.has(USER_ID)) {
						user_id = o.getString(USER_ID);	
					}
					event.put("key", user_id);
					
					if(user_id.isEmpty()) {
						System.err.println("WARN - " + USER_ID + " not found for event: " + o.toString(2));
						continue;
					}
					
					String eventTypeId = AMPLITUDE_PREFIX + "null";
					if(o.has("event_type")) {
						eventTypeId = AMPLITUDE_PREFIX + o.getString("event_type");
					}
					event.put("eventTypeId", eventTypeId);
					
					String serverUploadTime = o.getString("event_time");
					Date parsedServerTime;
					try {
						parsedServerTime = serverFormat.parse(serverUploadTime);
					} catch (ParseException pe) {
						parsedServerTime = serverFormat2.parse(serverUploadTime);
					}
					event.put("timestamp", parsedServerTime.getTime());
										
					JSONObject propertiesObj = new JSONObject();
					
					for(String propertyKey : STRING_PROPERTY_KEYS) {
						addStringProperty(o, propertiesObj, "", propertyKey);
					}
					
					if(o.has("user_properties")) {
						JSONObject userPropsObj = o.getJSONObject("user_properties");
						String[] userPropsKeys 
							= new String[] { "organizationPlatType", "role", "organizationName",
									"organizationSupportPlanType"};
						for(String userPropKey : userPropsKeys) {
							addStringProperty(userPropsObj, propertiesObj, "user_properties.", userPropKey);
						}
					}

					event.put("properties", propertiesObj);
					events.put(event);
									
				}
				CreateEvents creator = new CreateEvents(SPLIT_API_TOKEN, BATCH_SIZE);
				creator.doPost(events);
				
				System.out.println("INFO - processed " + events.length() + " events");
				if(events.length() > 0) {
					System.out.println("INFO - last event: " + events.getJSONObject(events.length() - 1).toString(2));
				}
			}
		} else {
			System.err.println("WARN - exiting with error on data extraction API call...");
		}
		
		System.out.println("INFO - finish in " + ((System.currentTimeMillis() - begin) / 1000) + "s");
	}
	
	private void addStringProperty(JSONObject src, JSONObject dest, String prefix, String name) {
		String value = "";
		if(src.has(name) && !src.isNull(name)) {
			value = src.getString(name);
		}
		dest.put(prefix + name, value);
	}
	
	public static String readFile(String path)
			throws IOException
	{
		byte[] encoded = Files.readAllBytes(Paths.get(path));
		return new String(encoded, Charset.defaultCharset());
	}
}
