/**
 * part of Search CCN project
 * http://www.iti.gr
 * 
 * @author theosem
 */
package gr.iti.ccn;


import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import javax.imageio.ImageIO;

import net.semanticmetadata.lire.imageanalysis.CEDD;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

import at.lux.imageanalysis.EdgeHistogramImplementation;
import at.lux.imageanalysis.VisualDescriptor;

public class SearchCCN implements CCNInterestListener, CCNFilterListener{
	
	protected static CCNHandle _handle;
	private static float percentage=0;
	private static String descriptors;
	private Interest initInterest;
	
	private static String _localDomain;
	
	protected static String _target_filePrefix;
	protected static File _targetDirectory;
	private BufferedImage _queryImage;
	
	private byte[] _search_id;
	
	public SearchCCN(){
		try{
			
		_handle = CCNHandle.open();
		
		_search_id = CCNTime.now().toBinaryTime();
		
		/*
		 * Here I am building the query String (Interest Content name)
		 */
		String qStr=new String();
		qStr = "ccnx:/search/"+_search_id.toString()+"/"; //search-id = 14
		/*for(int i=0;i<descriptors.size();i++){
			if(i==0){qStr += descriptors.get(i).toString(); continue;}
			qStr += "_";
			qStr += descriptors.get(i).toString();
		}*/
		qStr += descriptors;
		qStr += "/"+_localDomain+"/";//"/my_domain/"; //auto tha einai to domain pou tha epistrefoun ta dedomena (diladi edo)
		
		System.out.println("theosem search query: "+qStr);
		
		ContentName queryName = new ContentName(ContentName.fromURI(qStr));
		initInterest = VersioningProfile.firstBlockLatestVersionInterest(queryName, null);

		/*First step is to register a filter for this search id (prefix is the query search-id)
		 *in order to get the Interests that will come from nodes that have passed
		 * the similarity test 
		 */ 
		String prefixStr = new String();
		prefixStr += "ccnx:/"+_localDomain+"/search/"+_search_id.toString()+"/";
		ContentName prefix = new ContentName(ContentName.fromURI(prefixStr));
		_handle.registerFilter(prefix, this);

		/* Then I am expressing Interest for this search query
		 * to propagate the search through the CCN network
		 * (first register filter and then expressInterest oste na prolaveis
		 * apantiseis pou tixon na erthoun grigora...) 
		 */
		_handle.expressInterest(initInterest, this);
		
		}
		catch(MalformedContentNameStringException e){ System.out.println(e.getMessage());	}
		catch(ConfigurationException e){ System.out.println(e.getMessage()); }
		catch(IOException e){ System.out.println(e.getMessage()); }
	}
	
	
	private static void usage(){
		System.err.println("usage: SearchCCN fullpath_filename localDomain");
	}
	
	private static String readArgs(String[] args){
		percentage=0;
		//descriptors =new ArrayList<Double>();
		
		if (args.length < 2 /*|| args[1].toString().compareTo("desc") != 0*/ ) {
			usage();
			return null;
		}
		String queryImg = args[0];
		_localDomain = args[1];
		String[] comps = queryImg.split("/");
		
		_target_filePrefix="";
		for(int i=0;i<comps.length-1;i++){
			_target_filePrefix+= comps[i];
			_target_filePrefix+="/";
		}
		return args[0];
		/*for(int i=2;i<args.length;i++){
			if( args[i].toString().compareTo("perc") == 0 ){
				percentage = Float.valueOf(args[i+1]);
				break;
			}
			else
			{
				descriptors.add(Double.valueOf(( args[i] )));
			}
		} */
		
	}
	
	public void requestCollection(Interest interest){
		
		byte[] domain = interest.name().component(3);
		byte[] collectionName = interest.name().component(4);
		byte[] searchid = interest.name().component(2);
		
		ContentName nn=null;
		ArrayList<byte[]> nameComponents=new ArrayList<byte[]>();
		nameComponents.add(domain);
		nameComponents.add(collectionName);
		nameComponents.add(searchid);
			
		try {
			nn=new ContentName(ContentName.fromURI("ccnx:/"),nameComponents);
		} catch (MalformedContentNameStringException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		Interest pi = VersioningProfile.firstBlockLatestVersionInterest(nn, null);
		try {
			_handle.expressInterest(pi, this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
	}
	
	
public void sendAck(Interest interest){	
	//TODO: edo prepei na ftiakso ContentObject me SignedInfo (metadata tou content object)
	//pou tha to perigrafei san NACK ... oste na to kano discard xoris hacks apo to contentHandler
	
	try {
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
	}
	
	}

	//callback by implementation of CCNInterestListener interface
	public Interest handleContent(ArrayList<ContentObject> results, Interest interest)
	{
		System.out.println("========================================="); 
		System.out.println("INTEREST = "+interest.name().toString());
		System.out.println("=========================================");
		_handle.cancelInterest(interest, this);
		
		for (ContentObject CO : results) {
			System.out.println("new content");
			
			if(CO.isNACK()) { continue; }
			else if(CO.isData()){
				Log.info("got a data contentObject {0}",CO);	
				//byte[] content = CO.content();
				//System.out.println(content.toString());
				//System.out.println("byte length is "+content.length);
				
				CollectionObject clo=null;
				try {
					clo = new CollectionObject(CO,_handle);
				} catch (ContentDecodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				int numFiles=0;
				try {
					numFiles = clo.contents().size();
				} catch (ContentNotReadyException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ContentGoneException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			//	_handle.cancelInterest(interest, this);
				
				for(int i=0;i<numFiles;i++){
					Link ln=null;
					try {
						ln = clo.contents().get(i);
						try{
						//if(i==0 || i==1){
							//System.out.println("++++++++ START +++++++");
							System.out.println(ln.targetLabel());
							//System.out.println("++++++++ END +++++++");
							LocalSaveContentRetriever localsave = new LocalSaveContentRetriever(_handle, ln.targetName(),_target_filePrefix);
							Thread t = new Thread(localsave);
							t.start();
							while(localsave.a==0){
							System.out.print(".");	
							}
							
							
						//}
						}
						catch(Exception e){
							System.out.println("problem with output file");
							//
						}
					} catch (ContentNotReadyException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (ContentGoneException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					System.out.println("file: "+ln.targetName().toString());
				}
				
				}
		}
		System.out.println("Content handler");
		return null;
	}
	
	//callback by implementation of CCNFilterListener interface
	public int handleInterests(ArrayList<Interest> interests) {
		
			
		for (Interest interest : interests) {
			Log.info("search initiator: got an interest from a node: {0}", interest);
			System.out.println("interest name is:");
			System.out.println(interest.name().toString());
			System.out.println("++++++++++++++++++");
			/**
			* edo prepei na ftiakso to interest gia to sigkekrimeno collection apo to sigkekrimeno domain
			*/
			requestCollection(interest);
			sendAck(interest);
		}
		_handle.cancelInterest(initInterest, this);
		
		System.out.println("Interest handler");
		
		return 0;
	}
	

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		//diavazo tous descriptors kai to percentage. An den exo args kano print to usage kai exit.
		String query = readArgs(args);
		
		try {
			BufferedImage image = ImageIO.read( new File(query));
		
			CEDD cedd = new CEDD();
			cedd.extract(image);
			System.out.println(cedd.getStringRepresentation());
			descriptors = cedd.getStringRepresentation().substring(9);
			descriptors = descriptors.replace(" ", "_");
			//System.out.println(descriptors);
	
			//TODO edo prepei na kano to extract ton features tou query
			//oste na to peraso sto interest pou tha steilo...
			
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		//ed.extract(query);
		
		try{
		@SuppressWarnings("unused")
		SearchCCN proxy = new SearchCCN();		
		}
		catch(Exception e){
			//do nothing
		}
		
		
	}

}
