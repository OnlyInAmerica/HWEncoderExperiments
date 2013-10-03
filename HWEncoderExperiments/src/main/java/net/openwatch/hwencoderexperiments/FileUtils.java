package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

public class FileUtils {

    static final String TAG = "FileUtils";

    static final String OUTPUT_DIR = "HWEncodingExperiments";       // Directory relative to External or Internal (fallback) Storage

    /**
     * Returns a Java File initialized to a directory of given name
     * at the root storage location, with preference to external storage.
     * If the directory did not exist, it will be created at the conclusion of this call.
     * If a file with conflicting name exists, this method returns null;
     *
     * @param c the context to determine the internal storage location, if external is unavailable
     * @param directory_name the name of the directory desired at the storage location
     * @return a File pointing to the storage directory, or null if a file with conflicting name
     * exists
     */
    public static File getRootStorageDirectory(Context c, String directory_name){
        File result;
        // First, try getting access to the sdcard partition
        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
            Log.d(TAG,"Using sdcard");
            result = new File(Environment.getExternalStorageDirectory(), directory_name);
        } else {
            // Else, use the internal storage directory for this application
            Log.d(TAG,"Using internal storage");
            result = new File(c.getApplicationContext().getFilesDir(), directory_name);
        }

        if(!result.exists())
            result.mkdir();
        else if(result.isFile()){
            return null;
        }
        Log.d("getRootStorageDirectory", result.getAbsolutePath());
        return result;
    }

    /**
     * Returns a Java File initialized to a directory of given name
     * within the given location.
     *
     * @param parent_directory a File representing the directory in which the new child will reside
     * @return a File pointing to the desired directory, or null if a file with conflicting name
     * exists or if getRootStorageDirectory was not called first
     */
    public static File getStorageDirectory(File parent_directory, String new_child_directory_name){

        File result = new File(parent_directory, new_child_directory_name);
        if(!result.exists())
            if(result.mkdir())
                return result;
            else{
                Log.e("getStorageDirectory", "Error creating " + result.getAbsolutePath());
                return null;
            }
        else if(result.isFile()){
            return null;
        }

        Log.d("getStorageDirectory", "directory ready: " + result.getAbsolutePath());
        return result;
    }

    /**
     * Returns a TempFile with given root, filename, and extension.
     * The resulting TempFile is safe for use with Android's MediaRecorder
     * @param c
     * @param root
     * @param filename
     * @param extension
     * @return
     */
    public static File createTempFile(Context c, File root, String filename, String extension){
        File output = null;
        try {
            if(filename != null){
                if(!extension.contains("."))
                    extension = "." + extension;
                output = new File(root, filename + extension);
                output.createNewFile();
                //output = File.createTempFile(filename, extension, root);
                Log.i(TAG, "Created temp file: " + output.getAbsolutePath());
            }
            return output;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    public static File createTempFileInRootAppStorage(Context c, String filename){
        File recordingDir = FileUtils.getRootStorageDirectory(c, OUTPUT_DIR);
        return createTempFile(c, recordingDir, filename.split("\\.")[0], filename.split("\\.")[1]);
    }

}