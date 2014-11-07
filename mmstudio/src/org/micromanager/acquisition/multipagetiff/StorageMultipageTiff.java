///////////////////////////////////////////////////////////////////////////////
//FILE:          StorageMultipageTiff.java
//PROJECT:       Micro-Manager
//SUBSYSTEM:     mmstudio
//-----------------------------------------------------------------------------
//
// COPYRIGHT:    University of California, San Francisco, 2012
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package org.micromanager.acquisition.multipagetiff;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import mmcorej.TaggedImage;

import org.json.JSONException;
import org.json.JSONObject;

import org.micromanager.MMStudio;
import org.micromanager.api.data.Coords;
import org.micromanager.api.data.DisplaySettings;
import org.micromanager.api.data.Image;
import org.micromanager.api.data.Storage;
import org.micromanager.api.data.SummaryMetadata;
import org.micromanager.data.DefaultCoords;
import org.micromanager.data.DefaultDisplaySettings;
import org.micromanager.data.DefaultImage;
import org.micromanager.data.DefaultMetadata;
import org.micromanager.data.DefaultSummaryMetadata;
import org.micromanager.utils.ImageLabelComparator;
import org.micromanager.utils.JavaUtils;
import org.micromanager.utils.MDUtils;
import org.micromanager.utils.MMException;
import org.micromanager.utils.ProgressBar;
import org.micromanager.utils.ReportingUtils;


/**
 * This class provides image storage in the form of a single TIFF file that
 * contains all image data in the dataset.
 * Adapted from the TaggedImageStorageMultipageTiff module.
 */
public final class StorageMultipageTiff implements Storage {
   private DefaultSummaryMetadata summaryMetadata_;
   private DefaultDisplaySettings displaySettings_;
   private boolean newDataSet_;
   private int lastFrameOpenedDataSet_ = -1;
   private String directory_;
   final private boolean separateMetadataFile_;
   private boolean splitByXYPosition_ = true;
   private volatile boolean finished_ = false;
   private int numChannels_, numSlices_;
   private OMEMetadata omeMetadata_;
   private int lastFrame_ = 0;
   private int lastAcquiredPosition_ = 0;
   private ThreadPoolExecutor writingExecutor_;

   //map of position indices to objects associated with each
   private HashMap<Integer, FileSet> fileSets_;
   
   //Map of image labels to file 
   private TreeMap<String, MultipageTiffReader> tiffReadersByLabel_;
  
   public StorageMultipageTiff(String dir, Boolean newDataSet,
         DefaultSummaryMetadata summaryMetadata) throws IOException {
      this(dir, newDataSet, summaryMetadata,
            MMStudio.getInstance().getMetadataFileWithMultipageTiff(),
            MMStudio.getInstance().getSeparateFilesForPositionsMPTiff());
   }
   
   /*
    * Constructor that doesn't make reference to MMStudio so it can be used independently of MM GUI
    */
   public StorageMultipageTiff(String dir, boolean newDataSet,
         DefaultSummaryMetadata summaryMetadata, 
         boolean separateMDFile, boolean separateFilesForPositions) throws IOException {
      separateMetadataFile_ = separateMDFile;
      splitByXYPosition_ = separateFilesForPositions;

      newDataSet_ = newDataSet;
      directory_ = dir;
      tiffReadersByLabel_ = new TreeMap<String, MultipageTiffReader>(new ImageLabelComparator());
      setSummaryMetadata(summaryMetadata);

      // TODO: throw error if no existing dataset
      if (!newDataSet_) {       
         openExistingDataSet();
      }
   }
   
   private void processSummaryMD() {
      // TODO: get display settings from RAM storage? Or somewhere else...
      displaySettings_ = DefaultDisplaySettings.getStandardSettings();
      if (newDataSet_) {
         // TODO: this is (going to be) the current maximum, not the projected
         // maximum; is that a problem?
         numChannels_ = getMaxIndex("channel") + 1;
         numSlices_ = getMaxIndex("z") + 1;
      }
   }
   
   public ThreadPoolExecutor getWritingExecutor() {
      return writingExecutor_;
   }
   
   boolean slicesFirst() {
      return ((ImageLabelComparator) tiffReadersByLabel_.comparator()).getSlicesFirst();
   }
   
   boolean timeFirst() {
      return ((ImageLabelComparator) tiffReadersByLabel_.comparator()).getTimeFirst();
   }
   
   private void openExistingDataSet() {
      //Need to throw error if file not found
      MultipageTiffReader reader = null;
      File dir = new File(directory_);

      ProgressBar progressBar = new ProgressBar("Reading " + directory_, 0, dir.listFiles().length);
      int numRead = 0;
      progressBar.setProgress(numRead);
      progressBar.setVisible(true);
      for (File f : dir.listFiles()) {
         if (f.getName().endsWith(".tif") || f.getName().endsWith(".TIF")) {
            try {
               //this is where fixing dataset code occurs
               reader = new MultipageTiffReader(f);
               Set<String> labels = reader.getIndexKeys();
               for (String label : labels) {
                  tiffReadersByLabel_.put(label, reader);
                  int frameIndex = Integer.parseInt(label.split("_")[2]);
                  lastFrameOpenedDataSet_ = Math.max(frameIndex, lastFrameOpenedDataSet_);
               }
            } catch (IOException ex) {
               ReportingUtils.showError("Couldn't open file: " + f.toString());
            }
         }
         numRead++;
         progressBar.setProgress(numRead);
      }
      progressBar.setVisible(false);
      //reset this static variable to false so the prompt is delivered if a new data set is opened
      MultipageTiffReader.fixIndexMapWithoutPrompt_ = false;

      if (reader != null) {
         setSummaryMetadata(DefaultSummaryMetadata.legacyFromJSON(reader.getSummaryMetadata()), true);
         displaySettings_ = DefaultDisplaySettings.legacyFromJSON(reader.getDisplayAndComments());
      }

      progressBar.setProgress(1);
      progressBar.setVisible(false);
   }

   public TaggedImage getImage(int channelIndex, int sliceIndex, int frameIndex, int positionIndex) {
      String label = MDUtils.generateLabel(channelIndex, sliceIndex, frameIndex, positionIndex);
      if (!tiffReadersByLabel_.containsKey(label)) {
         return null;
      }

      //DEbugging code for a strange exception found in core log
      try {
         TaggedImage img = tiffReadersByLabel_.get(label).readImage(label);
         return img;     
      } catch (NullPointerException e) {
         ReportingUtils.logError("Couldn't find image that TiffReader is supposed to contain");
         if (tiffReadersByLabel_ == null) {
            ReportingUtils.logError("Tiffreadersbylabel is null");
         }
         if (tiffReadersByLabel_.get(label) == null) {
            ReportingUtils.logError("Specific reader is null " + label);
         }
      }
      return null;
   }

   public JSONObject getImageTags(int channelIndex, int sliceIndex, int frameIndex, int positionIndex) {
      String label = MDUtils.generateLabel(channelIndex, sliceIndex, frameIndex, positionIndex);
      if (!tiffReadersByLabel_.containsKey(label)) {
         return null;
      }
      return tiffReadersByLabel_.get(label).readImage(label).tags;   
   }

   /*
    * Method that allows overwrting of pixels but not MD or TIFF tags
    * so that low res stitched images can be written tile by tile
    * Not used by the Storage API, but can be useful for special applicaitons
    * of this class (e.g. Navigator plugin)
    */
   public void overwritePixels(Object pix, int channel, int slice, int frame, int position) throws IOException {
      //asumes only one position
      fileSets_.get(position).overwritePixels(pix, channel, slice, frame, position); 
   }
   
   public void putImage(TaggedImage taggedImage, boolean waitForWritingToFinish) throws MMException, InterruptedException, ExecutionException, IOException {
      putImage(taggedImage);
      if (waitForWritingToFinish) {
         Future f = writingExecutor_.submit(new Runnable() {
            @Override
            public void run() {
            }
         });
         f.get();
      }
   }

   public void putImage(TaggedImage taggedImage) throws MMException, IOException {
      if (!newDataSet_) {
         ReportingUtils.showError("Tried to write image to a finished data set");
         throw new MMException("This ImageFileManager is read-only.");
      }
      // initialize writing executor
      if (writingExecutor_ == null) {
         writingExecutor_ = new ThreadPoolExecutor(1, 1, 0,
               TimeUnit.NANOSECONDS,
               new LinkedBlockingQueue<java.lang.Runnable>());
      }
      int fileSetIndex = 0;
      if (splitByXYPosition_) {
         try {
            fileSetIndex = MDUtils.getPositionIndex(taggedImage.tags);
         } catch (JSONException ex) {
            ReportingUtils.logError(ex);
         }
      }
      String label = MDUtils.getLabel(taggedImage.tags);
      if (fileSets_ == null) {
         try {
            fileSets_ = new HashMap<Integer, FileSet>();
            JavaUtils.createDirectory(directory_);
         } catch (Exception ex) {
            ReportingUtils.logError(ex);
         }
      }
          
      if (omeMetadata_ == null) {
         omeMetadata_ = new OMEMetadata(this);
      }
      
      if (fileSets_.get(fileSetIndex) == null) {
         fileSets_.put(fileSetIndex, new FileSet(taggedImage.tags, this,
                  omeMetadata_,
                  splitByXYPosition_, separateMetadataFile_));
      }
      FileSet set = fileSets_.get(fileSetIndex);
      try {
         set.writeImage(taggedImage);
         tiffReadersByLabel_.put(label, set.getCurrentReader());
      } catch (IOException ex) {
        ReportingUtils.showError("problem writing image to file");
      }

         
      int frame;
      try {
         frame = MDUtils.getFrameIndex(taggedImage.tags);
      } catch (JSONException ex) {
         frame = 0;
      }
      lastFrameOpenedDataSet_ = Math.max(frame, lastFrameOpenedDataSet_);
   }

   public Set<String> imageKeys() {
      return tiffReadersByLabel_.keySet();
   }

   /**
    * Call this function when no more images are expected
    * Finishes writing the metadata file and closes it.
    * After calling this function, the imagestorage is read-only
    */
   @Override
   public synchronized void finished() {
      if (finished_) {
         return;
      }
      newDataSet_ = false;
      if (fileSets_ == null) {
         // Nothing to be done.
         finished_ = true;
         return;
      }
      ProgressBar progressBar = new ProgressBar("Finishing Files", 0, fileSets_.size());
      try {
         int count = 0;
         progressBar.setProgress(count);
         progressBar.setVisible(true);
         for (FileSet p : fileSets_.values()) {
            p.finishAbortedAcqIfNeeded();
         }
     
         try {
            //fill in missing tiffdata tags for OME meteadata--needed for acquisitions in which 
            //z and t arent the same for every channel
            for (int p = 0; p <= lastAcquiredPosition_; p++) {
               //set sizeT in case of aborted acq
               int currentFrame =  fileSets_.get(splitByXYPosition_ ? p : 0).getCurrentFrame();
               omeMetadata_.setNumFrames(p, currentFrame + 1);
               omeMetadata_.fillInMissingTiffDatas(lastAcquiredFrame(), p);
            }
         } catch (Exception e) {
            //don't want errors in this code to trip up correct file finishing
            ReportingUtils.logError("Couldn't fill in missing frames in OME");
         }

         //figure out where the full string of OME metadata can be stored 
         String fullOMEXMLMetadata = omeMetadata_.toString();
         int length = fullOMEXMLMetadata.length();
         String uuid = null, filename = null;
         FileSet master = null;
         for (FileSet p : fileSets_.values()) {
            if (p.hasSpaceForFullOMEXML(length)) {
               uuid = p.getCurrentUUID();
               filename = p.getCurrentFilename();
               p.finished(fullOMEXMLMetadata);
               master = p;
               count++;
               progressBar.setProgress(count);
               break;
            }
         }
         
         if (uuid == null) {
            //in the rare case that no files have extra space to fit the full
            //block of OME XML, generate a file specifically for holding it
            //that all other files can point to simplest way to do this is to
            //make a .ome text file 
             filename = "OMEXMLMetadata.ome";
             uuid = "urn:uuid:" + UUID.randomUUID().toString();
             PrintWriter pw = new PrintWriter(directory_ + File.separator + filename);
             pw.print(fullOMEXMLMetadata);
             pw.close();
         }
         
         String partialOME = OMEMetadata.getOMEStringPointerToMasterFile(filename, uuid);

         for (FileSet p : fileSets_.values()) {
            if (p == master) {
               continue;
            }
            p.finished(partialOME);
            count++;
            progressBar.setProgress(count);
         }            
         //shut down writing executor--pause here until all tasks have finished
         //writing so that no attempt is made to close the dataset (and thus
         //the FileChannel) before everything has finished writing mkae sure
         //all images have finished writing if they are on seperate thread 
         if (writingExecutor_ != null && !writingExecutor_.isShutdown()) {
            writingExecutor_.shutdown();
         }
      } catch (IOException ex) {
         ReportingUtils.logError(ex);
      }
      finally {
         progressBar.setVisible(false);
      }
      finished_ = true;
   }

   /**
    * Disposes of the tagged images in the imagestorage
    */
   @Override
   public void close() {
      for (MultipageTiffReader r : new HashSet<MultipageTiffReader>(tiffReadersByLabel_.values())) {
         try {
            r.close();
         } catch (IOException ex) {
            ReportingUtils.logError(ex);
         }
      }
   }

   @Override
   public boolean isFinished() {
      return !newDataSet_;
   }

   @Override
   public void setSummaryMetadata(SummaryMetadata summary) {
      setSummaryMetadata(summary, false);
   }
   
   private void setSummaryMetadata(SummaryMetadata summary,
         boolean showProgress) {
      summaryMetadata_ = summary;
      JSONObject summaryJSON = summary.legacyToJSON();
      if (summaryJSON != null) {
         boolean slicesFirst = summaryJSON.optBoolean("SlicesFirst", true);
         boolean timeFirst = summaryJSON.optBoolean("TimeFirst", false);
         TreeMap<String, MultipageTiffReader> oldImageMap = tiffReadersByLabel_;
         tiffReadersByLabel_ = new TreeMap<String, MultipageTiffReader>(new ImageLabelComparator(slicesFirst, timeFirst));
         if (showProgress) {
            ProgressBar progressBar = new ProgressBar("Building image location map", 0, oldImageMap.keySet().size());
            progressBar.setProgress(0);
            progressBar.setVisible(true);
            int i = 1;
            for (String label : oldImageMap.keySet()) {
               tiffReadersByLabel_.put(label, oldImageMap.get(label));
               progressBar.setProgress(i);
               i++;
            }
            progressBar.setVisible(false);
         } else {
            tiffReadersByLabel_.putAll(oldImageMap);
         }
         if (summaryJSON != null && summaryJSON.length() > 0) {
            processSummaryMD();
         }
      }
   }

   @Override
   public SummaryMetadata getSummaryMetadata() {
      return summaryMetadata_;
   }

   public boolean getSplitByStagePosition() {
      return splitByXYPosition_;
   }
   
   @Override
   public JSONObject getDisplayAndComments() {
      return displaySettings_;
   }

   @Override
   public void setDisplayAndComments(JSONObject settings) {
      displaySettings_ = settings;
   }
          
   @Override   
   public void writeDisplaySettings() {
      for (MultipageTiffReader r : new HashSet<MultipageTiffReader>(tiffReadersByLabel_.values())) {
         try {
            r.rewriteDisplaySettings(displaySettings_.getJSONArray("Channels"));
            r.rewriteComments(displaySettings_.getJSONObject("Comments"));
         } catch (JSONException ex) {
            ReportingUtils.logError("Error writing display settings");
         } catch (IOException ex) {
            ReportingUtils.logError(ex);
         }
      }
   }

   @Override
   public String getDiskLocation() {
      return directory_;
   }

   @Override
   public int lastAcquiredFrame() {
      if (newDataSet_) {
         return lastFrame_;
      } else {
         return lastFrameOpenedDataSet_;
      }
   }

   public void updateLastFrame(int frame) {
      lastFrame_ = Math.max(frame, lastFrame_);
   }

   public void updateLastPosition(int pos) {
      lastAcquiredPosition_ = Math.max(pos, lastAcquiredPosition_);
   }

   public int getNumSlices() {
      return numSlices_;
   }

   public int getNumChannels() {
      return numChannels_;
   }

   public long getDataSetSize() {
      File dir = new File (directory_);
      LinkedList<File> list = new LinkedList<File>();
      for (File f : dir.listFiles()) {
         if (f.isDirectory()) {
            for (File fi : f.listFiles()) {
               list.add(f);
            }
         } else {
            list.add(f);
         }
      }
      long size = 0;
      for (File f : list) {
         size += f.length();
      }
      return size;
   }

   @Override
   public int getNumImages() {
      ReportingUtils.logError("TODO: implement getNumImages");
      return 0;
   }

   @Override
   public DisplaySettings getDisplaySettings() {
      return displaySettings_;
   }

   @Override
   public Coords getMaxIndices() {
      ReportingUtils.logError("TODO: implement getMaxIndices");
      return null;
   }

   /**
    * TODO: in future there will probably be a cleaner way to implement this.
    */
   @Override
   public List<String> getAxes() {
      return getMaxIndices().getAxes();
   }

   @Override
   public Integer getMaxIndex(String axis) {
      return getMaxIndices().getPositionAt(axis);
   }

   @Override
   public List<Image> getImagesMatching(Coords coords) {
      ReportingUtils.logError("TODO: implement getImagesMatching");
      return null;
   }

   @Override
   public Image getImage(Coords coords) {
      ReportingUtils.logError("TODO: implement getImage");
      return null;
   }
}