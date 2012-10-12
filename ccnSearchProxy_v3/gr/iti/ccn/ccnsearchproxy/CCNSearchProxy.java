/**
 * A CCN Search Proxy program.
 * 
 * (Based on CCNx file proxy program.)
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package gr.iti.ccn.ccnsearchproxy;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import net.semanticmetadata.lire.imageanalysis.CEDD;
import net.sf.javaml.core.kdtree.KDTree;
import net.sf.javaml.core.kdtree.KeyDuplicateException;
import net.sf.javaml.core.kdtree.KeySizeException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileOutputStream;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.io.content.CollectionObjectTestRepo;

public class CCNSearchProxy implements CCNFilterListener, CCNInterestListener {
	
	static String DEFAULT_URI = "ccnx:/";
	static int BUF_SIZE = 4096;
	
	
	protected boolean _finished = false;
	protected ContentName _prefix; 
	protected String _shared_filePrefix;
	protected File _rootDirectory;
	protected CCNHandle _handle;
	private Interest _queryInterest=null; //incoming and holding the descriptors
	private Interest _replyInterest=null;//outgoing to reply that we have "similar" objects
	private Interest _collectionInterest=null;//incoming and requesting for the collection build after the search
	
	private ContentName _filtername=null;
	private String _strFilter = "ccnx:/TheUbuntuMachine";
	private ArrayList<Collection> _Collections;
	private Map<ContentName, Collection> collectionsMap;
	private Collection _globalCollection=null;
	private String _file_proxy_domain = null;
	private int counter;
	
	private KDTree _kdtreeIndex;
	
	public static void usage() {
		System.err.println("version 0.3 ");
		System.err.println("usage: CCNSearchProxy <file path to search> <domain for fileProxy> [<ccn prefix URI> default: ccn:/]");
	}

	public CCNSearchProxy(String filePrefix, String ccnxURI,String fileProxyDomain) throws MalformedContentNameStringException, ConfigurationException, IOException {
		counter=0;
		
		_prefix = ContentName.fromURI(ccnxURI);
		_file_proxy_domain = fileProxyDomain;
		_shared_filePrefix = filePrefix;
		_rootDirectory = new File(filePrefix);
		if (!_rootDirectory.exists()) {
			Log.severe("Cannot search in directory {0}: directory does not exist!", filePrefix);
			throw new IOException("Cannot search in directory " + filePrefix + ": directory does not exist!");
		}
		_handle = CCNHandle.open();
		
		_kdtreeIndex = new KDTree(144);
		loadIndex();
		
	}
	
	public void start() {
		Log.info("Starting search proxy for " + _shared_filePrefix + " on CCNx namespace " + _prefix + "...");
		System.out.println("Starting search proxy for " + _shared_filePrefix + " on CCNx namespace " + _prefix + "...");
		// All we have to do is say that we're listening on our main prefix.
		_handle.registerFilter(_prefix, this);
		
		/*try {
		_filtername = new ContentName(ContentName.fromURI(_strFilter));
		} catch (MalformedContentNameStringException e1) {
			e1.printStackTrace();
		}
		//edo kano register to localDomain pou tha perimeno gia na paro to interest gia to collection.
		_handle.registerFilter(_filtername, this);
		*/
	}
	
	public int handleInterests(ArrayList<Interest> interests) {
		// Alright, we've gotten an interest. Either it's an interest for a stream we're
		// already reading, or it's a request for a new stream.
		int count = 0;
		for (Interest interest : interests) {
			Log.info("CCNSearchProxy main responder: got new interest: {0}", interest);
			
			System.out.println("interest name is:");
			System.out.println(interest.name().toString());
			System.out.println("----------------");
			
			// Test to see if we need to respond to it.
			/*if (!_prefix.isPrefixOf(interest.name())) {
				Log.info("Unexpected: got an interest not matching our prefix (which is {0})", _prefix);
				continue;
			}*/
			if (_prefix.isPrefixOf(interest.name())) {
			
			// We see interests for all our segments, and the header. We want to only
			// handle interests for the first segment of a file, and not the first segment
			// of the header. Order tests so most common one (segments other than first, non-header)
			// fails first.
			if (SegmentationProfile.isSegment(interest.name()) && !SegmentationProfile.isFirstSegment(interest.name())) {
				Log.info("Got an interest for something other than a first segment, ignoring {0}.", interest.name());
				continue;
			}
			else if (interest.name().contains(CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION)) {
					try {
						Log.info("Got a name enumeration request: {0}", interest);
						nameEnumeratorResponse(interest);
					} catch (IOException e) {
						Log.warning("IOException generating name enumeration response to {0}: {1}: {2}", interest.name(), e.getClass().getName(), e.getMessage());
					}
					continue;
			} else if (SegmentationProfile.isHeader(interest.name())) {
				Log.info("Got an interest for the first segment of the header, ignoring {0}.", interest.name());
				continue;
			}
			//checking that this is the search initiaton Interest (search is the first component)
			else if( interest.name().containsWhere("search".getBytes()) == 0 ){
				//System.out.println("ooooooooooooooooooooooooo");
				if(_queryInterest == interest){ //check if this is the same interest. To eliminate duplicates
					Log.info("Got the same interest...returning. {0}.", interest.name());
					return 0;
				}
				_queryInterest = interest;
				/**
				 * send a content just to consume the interest.
				 * If another node/party already consumed the interest ..no problem this will 
				 * be discarded
				 * 
				 * TODO uncomment the following for testing...
				 */
				
				/*try {
					//CCNStringObject searchResponceStr = new CCNStringObject(interest.name(),"consume-the-interest",_handle);
					//boolean r = searchResponceStr.save(interest);
					
					Link[] link=new Link[1];
					try {
						link[0] = new Link(ContentName.fromURI("/dummyLink"));
					} catch (MalformedContentNameStringException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					Collection cl = new Collection(link);
					CollectionObject co = new CollectionObject( interest.name(), cl, _handle );
					boolean r = co.save(interest);
										
					if(r){
						System.out.println("ok responce saved");
						}
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}*/
				
				/**
				 * build the Interest name to reply based on the search interest that you received.
				 * you will transmit the interest only if you have similar content
				 */
				
				int num = interest.name().count();
				
				String search_id = ContentName.componentPrintNative(interest.name().component(1) );
				String domain = ContentName.componentPrintNative( interest.name().component(3) );
				
				String descriptors = ContentName.componentPrintNative(interest.name().component(2));
				//System.out.println("here theosem: "+descriptors);
				//here I will initialize the search in the local index using the available descritors
				System.out.println("DO THE ACTUAL SEARCHING ... ");
				
				ArrayList<String> similarfiles = findSimilars(descriptors);
				
			//	Collection cl = buildCollection();
				
				ContentName clName=null;
				counter++;
				try {
					clName=new ContentName(ContentName.fromURI(_file_proxy_domain+"/collection_"+counter));
				} catch (MalformedContentNameStringException e1) {
					e1.printStackTrace();
				}
				_globalCollection = buildCollection(similarfiles);
					
				//edo kano register to sigkekrimeno collection
				
				_handle.registerFilter(clName, this);
				_filtername = clName;	
				//_Collections.add(cl);
				
				//collectionsMap.put(clName, cl);
													
				ContentName nn=null;
				ArrayList<byte[]> nameComponents=new ArrayList<byte[]>();
				nameComponents.add(domain.getBytes());
				String str="search";
				nameComponents.add(str.getBytes());
				nameComponents.add(search_id.getBytes());
				
				for(int kk=0;kk< clName.count(); kk++){
					nameComponents.add(clName.component(kk));
				}
				/*str="localDomain";
				nameComponents.add(str.getBytes());
				str="collectionName";
				nameComponents.add(str.getBytes());
				*/
									
				try {
					nn=new ContentName(ContentName.fromURI("ccnx:/"),nameComponents);
					Log.info("name------------> {0} ", nn);
				//	filtername = new ContentName(ContentName.fromURI(strFilter));
				} catch (MalformedContentNameStringException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				
				// sinexizo mono an to search epestrepse antikeimena pou perasan to test.
				//reply with an interest registering availability of similar content..
				
				/**sto collectionName tha einai to full path pou tha prepei na zitisei to searchCCN application
				 * oste na parei ta content pou parasan to test 
				 */
				
				System.out.println(nn.toString());
				Interest pi = VersioningProfile.firstBlockLatestVersionInterest(nn, null);
				_replyInterest = pi;
								
				
				/* I am expressing Interest as a reply to the search query
				 * to point the collection name that the search initiator (searchCCN app) have to ask for. 
				 */
				try {
					_handle.expressInterest(pi, this);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
					
			continue;
			}
			}
			else if(_filtername.isPrefixOf(interest.name())){
				//TODO !!!
				//edo stelno to collection (to opoio einai collectionObject oste
				//na mporo na to steilo sto diktio)
				Log.info("collection interest: {0}", interest.name());
				System.out.println("sending collection to search initiator");
				try {
					CollectionObject cObj = new CollectionObject(interest.name(),_globalCollection,_handle);
					cObj.save(interest);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			continue;
			}			
			// Write the file
			/*try {
				if (writeFile(interest)) {
					count++;
				}
			} catch (IOException e) {
				Log.warning("IOException writing file {0}: {1}: {2}", interest.name(), e.getClass().getName(), e.getMessage());
			}*/
		}
		return count;
	}
	
	protected File ccnNameToFilePath(ContentName name) {
		
		ContentName fileNamePostfix = name.postfix(_prefix);
		if (null == fileNamePostfix) {
			// Only happens if interest.name() is not a prefix of _prefix.
			Log.info("Unexpected: got an interest not matching our prefix (which is {0})", _prefix);
			return null;
		}

		File fileToWrite = new File(_rootDirectory, fileNamePostfix.toString());
		Log.info("file postfix {0}, resulting path name {1}", fileNamePostfix, fileToWrite.getAbsolutePath());
		return fileToWrite;
	}
	
	protected Collection buildCollection(ArrayList<String> files){
		//TODO edo grafo dummy files ...prepei na valo ta files pou pername to search...
		Link[] links=new Link[files.size()];
		for(int i=0;i<files.size();i++){
			String str = _file_proxy_domain+files.get(i);
			try {
				links[i] = new Link(ContentName.fromURI(str),"1 2 3 4 5 6 "+i,null);
			} catch (MalformedContentNameStringException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		Collection cl= new Collection(links);
		return cl;
		/*String str2 = _file_proxy_domain+"montesanto.jpg";
		String str3 = _file_proxy_domain+"cat.jpg";
		for(int i=0;i<4;i++){
			String str=_file_proxy_domain+"FileNum_"+i;
			try {
				if(i==0)
					links[i] = new Link(ContentName.fromURI(str2));
				else if(i==1)
					links[i] = new Link(ContentName.fromURI(str3));
				else
				links[i] = new Link(ContentName.fromURI(str));
			} catch (MalformedContentNameStringException e) {
				e.printStackTrace();
			}
		} 
		Collection cl= new Collection(links);
		return cl;
		*/
	}
	protected void loadIndex(){
		
		File folder = new File(_shared_filePrefix);
		File[] listOfFiles = folder.listFiles();
		System.out.println("start loading index");
		for(int i=0;i<listOfFiles.length;i++){
			if(listOfFiles[i].isFile()){
				File theFile = listOfFiles[i].getAbsoluteFile();
				String filename = listOfFiles[i].getName();
				BufferedImage image=null;
				try {
					image = ImageIO.read( theFile );
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				CEDD cedd = new CEDD();
				cedd.extract(image);
				String filedes = cedd.getStringRepresentation().substring(9);
				String[] fileDescr = filedes.split(" ");
				double[] descriptors=new double[144];
				for(int k=0;k<fileDescr.length;k++){
					descriptors[k] = Double.parseDouble(fileDescr[k]);
				}
			try {
				_kdtreeIndex.insert(descriptors,filename );
			} catch (KeySizeException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (KeyDuplicateException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			}
			}
	System.out.println("_ INDEX IS LOADED _");	
	}
	
	protected ArrayList<String> findSimilars(String query){
		ArrayList<String> similarFiles=new ArrayList<String>();
		String[] queryDescr = query.split("_");
		
		double[] q= new double[144];
		Object[] similars=null;
		for(int i=0;i<queryDescr.length;i++ ){
			q[i]=Integer.parseInt( queryDescr[i] );
		}
		try {
			similars =  _kdtreeIndex.nearest(q, 5);
		} catch (IllegalArgumentException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (KeySizeException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		for(int k=0;k<similars.length;k++){
			similarFiles.add(similars[k].toString());
		}
	return similarFiles;
	}
	
	
	/**
	 * Handle name enumeration requests
	 * 
	 * @param interest
	 * @throws IOException 
	 */
	public void nameEnumeratorResponse(Interest interest) throws IOException {
		
		ContentName neRequestPrefix = interest.name().cut(CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION);
		
		File directoryToEnumerate = ccnNameToFilePath(neRequestPrefix);
		
		if (!directoryToEnumerate.exists() || !directoryToEnumerate.isDirectory()) {
			// nothing to enumerate
			return;
		}
		
		NameEnumerationResponse ner = new NameEnumerationResponse();
		ner.setPrefix(new ContentName(neRequestPrefix, CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION));
		
		Log.info("Directory to enumerate: {0}, last modified {1}", directoryToEnumerate.getAbsolutePath(), new CCNTime(directoryToEnumerate.lastModified()));
		// stat() the directory to see when it last changed -- will change whenever
		// a file is added or removed, which is the only thing that will change the
		// list we return.
		ner.setTimestamp(new CCNTime(directoryToEnumerate.lastModified()));
		// See if the resulting response is later than the previous one we released.
	    ContentName potentialCollectionName = VersioningProfile.addVersion(ner.getPrefix(), ner.getTimestamp());
	    potentialCollectionName = SegmentationProfile.segmentName(potentialCollectionName, SegmentationProfile.baseSegment());
		//check if we should respond...
		if (interest.matches(potentialCollectionName, null)) {
		
			// We want to set the version of the NE response to the time of the 
			// last modified file in the directory. Unfortunately that requires us to
			// stat() all the files whether we are going to respond or not.
			String [] children = directoryToEnumerate.list();
			
			if ((null != children) && (children.length > 0)) {
				for (int i = 0; i < children.length; ++i) {
					ner.add(children[i]);
				}

				Collection cd = ner.getNamesInCollectionData();
				CollectionObject co = new CollectionObject(ner.getPrefix(), cd, _handle);
				co.save(ner.getTimestamp(), interest);
				Log.info("sending back name enumeration response {0}, timestamp (version) {1}.", ner.getPrefix(), ner.getTimestamp());
			} else {
				Log.info("no children available: we are not sending back a response to the name enumeration interest (interest = {0}); our response would have been {1}", interest, potentialCollectionName);
			}
		} else {
			Log.info("we are not sending back a response to the name enumeration interest (interest = {0}); our response would have been {1}", interest, potentialCollectionName);
			Exclude.Element el = interest.exclude().value(1);
			if ((null != el) && (el instanceof ExcludeComponent)) {
				Log.info("previous version: {0}", VersioningProfile.getVersionComponentAsTimestamp(((ExcludeComponent)el).getBytes()));
			}
		}
	}

    /**
     * Turn off everything.
     * @throws IOException 
     */
	public void shutdown() throws IOException {
		if (null != _handle) {
			_handle.unregisterFilter(_prefix, this);
			Log.info("Shutting down file proxy for " + _shared_filePrefix + " on CCNx namespace " + _prefix + "...");
			System.out.println("Shutting down file proxy for " + _shared_filePrefix + " on CCNx namespace " + _prefix + "...");
		}
		_finished = true;
	}
	
	public boolean finished() { return _finished; }

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		if (args.length < 2) {
			usage();
			return;
		}
		
		
		String filePrefix = args[0];
		String FileProxyDomain = args[1];
		String ccnURI = (args.length > 2) ? args[2] : DEFAULT_URI;
		
//		TODO 
	/*	try {
			String systemCommand="ccnfileproxy ";
			systemCommand+= filePrefix+" ";
			systemCommand += FileProxyDomain;
			Process proc=Runtime.getRuntime().exec(systemCommand);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		*/
		try {
			CCNSearchProxy proxy = new CCNSearchProxy(filePrefix, ccnURI,FileProxyDomain);
			
			// All we need to do now is wait until interrupted.
			proxy.start();
			
			while (!proxy.finished()) {
				// we really want to wait until someone ^C's us.
				try {
					Thread.sleep(100000);
				} catch (InterruptedException e) {
					// do nothing
				}
			}
		} catch (Exception e) {
			Log.warning("Exception in ccnFileProxy: type: " + e.getClass().getName() + ", message:  "+ e.getMessage());
			Log.warningStackTrace(e);
			System.err.println("Exception in ccnFileProxy: type: " + e.getClass().getName() + ", message:  "+ e.getMessage());
			e.printStackTrace();
		}
	}

	public Interest handleContent(ArrayList<ContentObject> arg0, Interest arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	public int handleInterests(ArrayList arg0) {
		// TODO Auto-generated method stub
		return 0;
	}

	public Interest handleContent(ArrayList arg0, Interest arg1) {
		// TODO Auto-generated method stub
		return null;
	}
}
