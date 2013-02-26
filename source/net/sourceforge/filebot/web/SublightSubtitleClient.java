
package net.sourceforge.filebot.web;


import static java.lang.Math.*;
import static java.util.Collections.*;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.Icon;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;

import net.sourceforge.filebot.ResourceManager;
import net.sourceforge.tuned.Timer;
import net.sublight.webservice.ArrayOfGenre;
import net.sublight.webservice.ArrayOfIMDB;
import net.sublight.webservice.ArrayOfRelease;
import net.sublight.webservice.ArrayOfString;
import net.sublight.webservice.ArrayOfSubtitle;
import net.sublight.webservice.ArrayOfSubtitleLanguage;
import net.sublight.webservice.ClientInfo;
import net.sublight.webservice.Genre;
import net.sublight.webservice.IMDB;
import net.sublight.webservice.Release;
import net.sublight.webservice.Sublight;
import net.sublight.webservice.SublightSoap;
import net.sublight.webservice.Subtitle;
import net.sublight.webservice.SubtitleLanguage;


public class SublightSubtitleClient implements SubtitleProvider, VideoHashSubtitleService {
	
	private static final String iid = "25f30171-518c-463b-a310-b9f8e1eddb40";
	
	private final ClientInfo clientInfo = new ClientInfo();
	
	private String username;
	private String passwordHash;
	
	private SublightSoap webservice;
	private String session;
	
	
	@Override
	public String getName() {
		return "Sublight";
	}
	
	
	@Override
	public URI getLink() {
		return URI.create("http://www.sublight.si");
	}
	
	
	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.sublight");
	}
	
	
	@Override
	public List<SearchResult> search(String query) throws WebServiceException {
		// require login
		login();
		
		Holder<ArrayOfIMDB> response = new Holder<ArrayOfIMDB>();
		Holder<String> error = new Holder<String>();
		
		webservice.findIMDB(query, null, null, response, error);
		
		// abort if something went wrong
		checkError(error);
		
		List<SearchResult> results = new ArrayList<SearchResult>();
		
		if (response.value != null) {
			for (IMDB imdb : response.value.getIMDB()) {
				// remove classifier (e.g. tt0436992 -> 0436992) 
				int id = Integer.parseInt(imdb.getId().substring(2));
				
				results.add(new Movie(imdb.getTitle(), imdb.getYear(), id, -1));
			}
		}
		
		return results;
	}
	
	
	@Override
	public List<SubtitleDescriptor> getSubtitleList(SearchResult searchResult, String languageName) throws WebServiceException {
		Movie movie = (Movie) searchResult;
		
		List<SubtitleDescriptor> subtitles = new ArrayList<SubtitleDescriptor>();
		
		// retrieve subtitles by name and year
		for (Subtitle subtitle : getSubtitleList(null, movie.getName(), movie.getYear(), languageName)) {
			subtitles.add(new SublightSubtitleDescriptor(subtitle, this));
		}
		
		return subtitles;
	}
	
	
	@Override
	public Map<File, List<SubtitleDescriptor>> getSubtitleList(File[] files, final String languageName) throws Exception {
		Map<File, List<SubtitleDescriptor>> subtitles = new HashMap<File, List<SubtitleDescriptor>>(files.length);
		
		ExecutorService executor = Executors.newFixedThreadPool(min(files.length, 10));
		List<Future<List<SubtitleDescriptor>>> requests = new ArrayList<Future<List<SubtitleDescriptor>>>();
		
		try {
			// queue and execute requests
			for (int i = 0; i < files.length; i++) {
				Future<List<SubtitleDescriptor>> request = null;
				
				try {
					// make call interruptible
					if (Thread.interrupted())
						throw new InterruptedException();
					
					// compute video hash and execute query in parallel
					final String videoHash = SublightVideoHasher.computeHash(files[i]);
					
					request = executor.submit(new Callable<List<SubtitleDescriptor>>() {
						
						@Override
						public List<SubtitleDescriptor> call() throws Exception {
							return getSubtitleList(videoHash, languageName);
						}
					});
				} catch (IOException e) {
					Logger.getLogger(SublightSubtitleClient.class.getName()).log(Level.WARNING, "Error computing video hash: " + e.getMessage());
				}
				
				requests.add(i, request);
			}
			
			// collect results
			for (int i = 0; i < files.length; i++) {
				List<SubtitleDescriptor> response = emptyList();
				
				if (requests.get(i) != null) {
					response = requests.get(i).get();
				}
				
				subtitles.put(files[i], response);
			}
			
			return subtitles;
		} finally {
			// shutdown after all tasks are done or an exception was thrown 
			executor.shutdownNow();
		}
	}
	
	
	public List<SubtitleDescriptor> getSubtitleList(String videoHash, String languageName) throws WebServiceException {
		List<SubtitleDescriptor> subtitles = new ArrayList<SubtitleDescriptor>();
		
		// retrieve subtitles by video hash
		for (Subtitle subtitle : getSubtitleList(videoHash, null, null, languageName)) {
			// only keep linked subtitles
			if (subtitle.isIsLinked()) {
				subtitles.add(new SublightSubtitleDescriptor(subtitle, this));
			}
		}
		
		return subtitles;
	}
	
	
	public List<Subtitle> getSubtitleList(String videoHash, String name, Integer year, String languageName) throws WebServiceException {
		// require login
		login();
		
		// given language or all languages
		ArrayOfSubtitleLanguage languages = new ArrayOfSubtitleLanguage();
		
		if (languageName != null) {
			// given language
			languages.getSubtitleLanguage().add(getSubtitleLanguage(languageName));
		} else {
			// all languages
			Collections.addAll(languages.getSubtitleLanguage(), SubtitleLanguage.values());
		}
		
		// hash singleton array
		ArrayOfString videoHashes = new ArrayOfString();
		videoHashes.getString().add(videoHash);
		
		// all genres
		ArrayOfGenre genres = new ArrayOfGenre();
		Collections.addAll(genres.getGenre(), Genre.values());
		
		// response holders
		Holder<ArrayOfSubtitle> subtitles = new Holder<ArrayOfSubtitle>();
		Holder<ArrayOfRelease> releases = new Holder<ArrayOfRelease>();
		Holder<String> error = new Holder<String>();
		
		webservice.searchSubtitles4(session, videoHashes, name, year, null, null, languages, genres, null, null, null, subtitles, releases, null, error);
		
		// abort if something went wrong
		checkError(error);
		
		// return empty list if response is empty
		if (subtitles.value == null) {
			return Collections.emptyList();
		}
		
		// map all release names by subtitle id
		if (releases.value != null) {
			Map<String, String> releaseNameBySubtitleID = new HashMap<String, String>();
			
			// map release names by subtitle id
			for (Release release : releases.value.getRelease()) {
				releaseNameBySubtitleID.put(release.getSubtitleID(), release.getName());
			}
			
			// set release names
			for (Subtitle subtitle : subtitles.value.getSubtitle()) {
				subtitle.setRelease(releaseNameBySubtitleID.get(subtitle.getSubtitleID()));
			}
		}
		
		return subtitles.value.getSubtitle();
	}
	
	
	@Override
	public boolean publishSubtitle(int imdbid, String languageName, File videoFile, File subtitleFile) throws Exception {
		//TODO implement upload feature
		return false;
	}
	
	
	public void publishSubtitle(int imdbid, String videoHash, String languageName, String releaseName, byte[] data) {
		// require login
		login();
		
		Subtitle subtitle = new Subtitle();
		subtitle.setIMDB(String.format("http://www.imdb.com/title/tt%07d", imdbid));
		subtitle.setLanguage(getSubtitleLanguage(languageName));
		subtitle.setRelease(releaseName);
		
		Holder<Boolean> result = new Holder<Boolean>();
		Holder<String> subid = new Holder<String>();
		Holder<String> error = new Holder<String>();
		
		// upload subtitle
		webservice.publishSubtitle2(session, subtitle, data, result, subid, null, error);
		
		// abort if something went wrong
		checkError(error);
		
		// link subtitle to video file
		webservice.addHashLink3(session, subid.value, videoHash, null, null, error);
		
		// abort if something went wrong
		checkError(error);
	}
	
	
	protected Map<String, SubtitleLanguage> getLanguageAliasMap() {
		Map<String, SubtitleLanguage> languages = new HashMap<String, SubtitleLanguage>(4);
		
		// insert special some additional special handling
		languages.put("Brazilian", SubtitleLanguage.PORTUGUESE_BRAZIL);
		languages.put("Bosnian", SubtitleLanguage.BOSNIAN_LATIN);
		languages.put("Serbian", SubtitleLanguage.SERBIAN_LATIN);
		
		return languages;
	}
	
	
	protected SubtitleLanguage getSubtitleLanguage(String languageName) {
		// check subtitle language enum
		for (SubtitleLanguage language : SubtitleLanguage.values()) {
			if (language.value().equalsIgnoreCase(languageName))
				return language;
		}
		
		// check alias list
		for (Entry<String, SubtitleLanguage> alias : getLanguageAliasMap().entrySet()) {
			if (alias.getKey().equalsIgnoreCase(languageName))
				return alias.getValue();
		}
		
		// illegal language name
		throw new IllegalArgumentException("Illegal language: " + languageName);
	}
	
	
	protected String getLanguageName(SubtitleLanguage language) {
		// check alias list first
		for (Entry<String, SubtitleLanguage> alias : getLanguageAliasMap().entrySet()) {
			if (language == alias.getValue())
				return alias.getKey();
		}
		
		// use language value by default
		return language.value();
	}
	
	
	protected synchronized byte[] getZipArchive(Subtitle subtitle) throws WebServiceException, InterruptedException {
		// require login
		login();
		
		Holder<String> ticket = new Holder<String>();
		Holder<Short> que = new Holder<Short>();
		Holder<byte[]> data = new Holder<byte[]>();
		Holder<String> error = new Holder<String>();
		
		webservice.getDownloadTicket2(session, null, subtitle.getSubtitleID(), null, ticket, que, null, error);
		
		// abort if something went wrong
		checkError(error);
		
		// wait x seconds as specified by the download ticket response, download ticket is not valid until then
		Thread.sleep(que.value * 1000);
		
		webservice.downloadByID4(session, subtitle.getSubtitleID(), -1, false, ticket.value, null, data, null, error);
		
		// abort if something went wrong
		checkError(error);
		
		// return zip file bytes
		return data.value;
	}
	
	
	@Override
	public URI getSubtitleListLink(SearchResult searchResult, String languageName) {
		// note that sublight can only be accessed via the soap API
		return URI.create("http://www.sublight.si/SearchSubtitles.aspx");
	}
	
	
	public synchronized void setClient(String id, String key) {
		clientInfo.setClientId(id);
		clientInfo.setApiKey(key);
	}
	
	
	public synchronized void setUser(String username, String password) {
		this.username = username;
		this.passwordHash = password != null && password.length() > 0 ? getPasswordHash(password) : null;
	}
	
	
	public String getPasswordHash(String password) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(password.getBytes("UTF-16LE"));
			return String.format("%032x", new BigInteger(1, digest.digest()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	
	public synchronized void login() throws WebServiceException {
		if (clientInfo.getClientId() == null || clientInfo.getClientId().isEmpty()) {
			throw new IllegalStateException("Sublight login has not been configured");
		}
		
		if (webservice == null) {
			// lazy initialize because all the JAX-WS class loading can take quite some time
			webservice = new Sublight().getSublightSoap();
		}
		
		if (session == null) {
			// args contains only iid
			ArrayOfString args = new ArrayOfString();
			args.getString().add(iid);
			
			Holder<String> session = new Holder<String>();
			Holder<String> error = new Holder<String>();
			
			if (username == null || username.isEmpty()) {
				webservice.logInAnonymous4(clientInfo, args, session, null, error);
			} else {
				webservice.logIn6(username, passwordHash, clientInfo, args, session, null, null, null, null, error);
			}
			
			// abort if something went wrong
			checkError(error);
			
			// start session
			this.session = session.value;
		}
		
		// reset timer
		logoutTimer.set(10, TimeUnit.MINUTES, true);
	}
	
	
	protected synchronized void logout() throws WebServiceException {
		if (session != null) {
			Holder<String> error = new Holder<String>();
			
			webservice.logOut(session, null, error);
			
			// abort if something went wrong
			checkError(error);
			
			// stop session
			this.session = null;
			
			// cancel timer
			logoutTimer.cancel();
		}
	}
	
	
	protected void checkError(Holder<?> error) throws WebServiceException {
		if (error.value != null) {
			throw new WebServiceException("Response indicates error: " + error.value);
		}
	}
	
	protected final Timer logoutTimer = new Timer() {
		
		@Override
		public void run() {
			logout();
		}
	};
	
}
