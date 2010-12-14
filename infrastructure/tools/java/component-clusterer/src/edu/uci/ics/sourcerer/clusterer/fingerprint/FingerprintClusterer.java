/* 
 * Sourcerer: an infrastructure for large-scale source code analysis.
 * Copyright (C) by contributors. See CONTRIBUTORS.txt for full list.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package edu.uci.ics.sourcerer.clusterer.fingerprint;

import static edu.uci.ics.sourcerer.util.io.Logging.logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;

import edu.uci.ics.sourcerer.clusterer.fqn.FqnClusterer;
import edu.uci.ics.sourcerer.clusterer.stats.FileCluster;
import edu.uci.ics.sourcerer.clusterer.stats.Matching;
import edu.uci.ics.sourcerer.db.queries.DatabaseAccessor;
import edu.uci.ics.sourcerer.db.util.DatabaseConnection;
import edu.uci.ics.sourcerer.util.Helper;
import edu.uci.ics.sourcerer.util.io.FileUtils;
import edu.uci.ics.sourcerer.util.io.Properties;
import edu.uci.ics.sourcerer.util.io.Property;
import edu.uci.ics.sourcerer.util.io.properties.StringProperty;

/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class FingerprintClusterer {
  public static final Property<String> FINGERPRINT_FILE_LISTING = new StringProperty("fingerprint-file-listing.", "fingerprint-file-list.sh", "List of all the files (and the types inside the files) in the repository.");
  
  private static class FingerprintDatabaseAccessor extends DatabaseAccessor {
    public FingerprintDatabaseAccessor(DatabaseConnection connection) {
      super(connection);
    }
    
    public Collection<String> getContainedFQNs(Integer entityID) {
      return joinQueries.getContainedFQNs(entityID);
    }
  }
  
  public static void generateFileListing() {
    DatabaseConnection conn = new DatabaseConnection();
    if (conn.open()) {
      FingerprintDatabaseAccessor accessor = null;
      BufferedReader br = null;
      BufferedWriter bw = null;
      try {
        accessor = new FingerprintDatabaseAccessor(conn);
        br = new BufferedReader(new FileReader(new File(Properties.OUTPUT.getValue(), FqnClusterer.FQN_FILE_LISTING.getValue())));
        bw = new BufferedWriter(new FileWriter(new File(Properties.OUTPUT.getValue(), FINGERPRINT_FILE_LISTING.getValue())));
        int count = 0;
        for (String line = br.readLine(); line != null; line = br.readLine()) {
          String[] parts = line.split(" ");
          if (parts.length == 6) {
            if (++count % 10000 == 0) {
              logger.info(count + " rows written");
            }
            try {
              Integer entityID = Integer.valueOf(parts[5]);
              bw.write(line);
              for (String fqn : accessor.getContainedFQNs(entityID)) {
                bw.write(" ");
                bw.write(fqn);
              }
              bw.write("\n");
            } catch (NumberFormatException e) {
              logger.log(Level.SEVERE, "Invalid line: " + line, e);
            }
          } else {
            logger.log(Level.SEVERE, "Invalid line: " + line);
          }
        }
        logger.info("Done!");
      } catch (IOException e) {
        logger.log(Level.SEVERE, "Error writing/reading to/from file", e);
      } finally {
        FileUtils.close(accessor);
        FileUtils.close(br);
        FileUtils.close(bw);
      }
    }
  }
  
  public static Matching getMatching() {
    logger.info("Processing fingerprint file listing...");
    
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(new File(Properties.INPUT.getValue(), FINGERPRINT_FILE_LISTING.getValue())));
      
      Matching matching = new Matching();
      Map<FingerprintMatcher, FileCluster> files = Helper.newHashMap();
      for (String line = br.readLine(); line != null; line = br.readLine()) {
        try {
          String[] parts = line.split(" ");
          if (parts.length >= 6) {
            String[] names = new String[parts.length - 5];
            int startIdx = parts[4].lastIndexOf('.') + 1; 
            names[0] = parts[4].substring(startIdx);
            for (int i = 6; i < parts.length; i++) {
              names[i - 5] = parts[i].substring(startIdx);
            }
            Arrays.sort(names, 1, names.length);
            FingerprintMatcher matcher = new FingerprintMatcher(names);
            FileCluster cluster = files.get(matcher);
            if (cluster == null) {
              cluster = new FileCluster();
              files.put(matcher, cluster);
              matching.addCluster(cluster);
            }
            cluster.addFile(parts[0], parts[2]);
          } else {
            logger.log(Level.SEVERE, "Invalid line: " + line);
          }
        } catch (IndexOutOfBoundsException e) {
          logger.log(Level.SEVERE, "Invalid line (substring problem): " + line, e);
        }
      }
      return matching;
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error in reading file listing.", e);
      return null;
    } finally {
      FileUtils.close(br);
    }
  }
}
