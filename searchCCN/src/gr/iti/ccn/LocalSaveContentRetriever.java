package gr.iti.ccn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.protocol.ContentName;


/**
 * Class for retrieving content on a separate thread.  This is called by the
 * ContentExplorer for displaying txt in the preview pane, popup windows, saving
 * content to a local filesystem and for interfacing with media players (upcoming).
 */
public class LocalSaveContentRetriever implements Runnable {

	public int a=0;
	private ContentName name = null;
	private CCNHandle handle = null;
	private int readsize = 1024;
	private String _directory=null;
	//private JEditorPane htmlPane = null;
	
	/**
	 * Constructor for the LocalSaveContentRetriever.
	 * 
	 * @param h CCNHandle to use for downloading the content
	 * @param n ContentName of the content object name to download
	 * @param p Preview pane to show download status
	 */
	public LocalSaveContentRetriever(CCNHandle h, ContentName n,String directory){
		handle = h;
		name = n;
		_directory = directory;
		//htmlPane = p;
	}
	
	
	/**
	 * run() method for the thread that saves content to a filesystem.  This method
	 * creates and displays a JFileChooser.  The user selects a location to save the
	 * content.  If the selected file cannot be created or written to, the method
	 * returns and the content is not retrieved.  Status for this operation is displayed
	 * in the ContentExplorer preview pane.
	 * 
	 */
	public void run() {
		System.out.println("theosem: writing file in separate thread..");
		if (name == null) {
			System.err.println("Must set file name for retrieval");
			return;
		}
				
		if (handle == null) {
			System.err.println("Must set CCNHandle");
			return;
		}
		
		String tmpName="";
		for(int kk=0;kk<name.count();kk++){
			tmpName += "_";
			tmpName += name.stringComponent(kk);
		}
		if( tmpName.contentEquals("_dummyLink") ) {
			System.out.println("pira /dummyLink !!!");
			return;}
		File f = new File(_directory,tmpName);//,name.toString());
		
        
		boolean overwrite = false;
        //check the file and make sure we can write to it
        try {
        	if (f.exists() ) {
        		//the file exists...   don't need to create a new file
        		System.out.println("theosem:I could overwrite the file (filename exists)");
        		overwrite = true;
        		//theosem: vasika an to file uparxei den to ksana grafo ..kano exit
        		System.out.println("theosem:I will skip this file...returning from thread.");
        		return;
        		
        	} else {
        	//we need to create the file
        		f.createNewFile();
        	}
        	
        	if (f.canWrite()) {
        	} else {
        		System.out.println("The ContentExplorer is unable to write the content to the specified file.");
        		return;
        	}
        } catch (IOException e) {
        	System.err.println("could not create "+f.getPath() +" for saving content to filesystem");
          }
        		
		try{
			if (!overwrite)
				System.out.println("saving "+name+" to "+ f.getCanonicalPath());
			else
				System.out.println("overwriting contents of "+ f.getCanonicalPath()+" to save "+name);
			
			CCNFileInputStream fis = new CCNFileInputStream(name, handle);
			FileOutputStream output = new FileOutputStream(f);
			
			byte [] buffer = new byte[readsize];
			
			int readcount = 0;
			int readtotal = 0;
			while ((readcount = fis.read(buffer)) != -1){
				readtotal += readcount;
				output.write(buffer, 0, readcount);
				output.flush();
			}
			System.out.println("Saved "+name+" to "+f.getCanonicalPath());	
		} catch (Exception e) {
			System.err.println("Could not retrieve file: "+name);
		}
		
	a=1;
	}

}
