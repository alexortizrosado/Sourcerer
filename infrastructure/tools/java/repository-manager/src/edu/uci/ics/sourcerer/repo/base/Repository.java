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
package edu.uci.ics.sourcerer.repo.base;

import static edu.uci.ics.sourcerer.util.io.Logging.RESUME;
import static edu.uci.ics.sourcerer.util.io.Logging.logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import edu.uci.ics.sourcerer.repo.AbstractRepository;
import edu.uci.ics.sourcerer.repo.IndexedJar;
import edu.uci.ics.sourcerer.repo.JarIndex;
import edu.uci.ics.sourcerer.repo.RepoJar;
import edu.uci.ics.sourcerer.util.Helper;
import edu.uci.ics.sourcerer.util.io.FileUtils;
import edu.uci.ics.sourcerer.util.io.Logging;

/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class Repository extends AbstractRepository {
  private File tempDir;
  private Map<String, RepoProject> projects;
  
  private Repository(File repoRoot, File tempDir) {
    super(repoRoot);
    this.tempDir = tempDir;
    projects = Helper.newHashMap();
  }
  
  @Override
  protected void addFile(File checkout) {
    File content = new File(checkout, "content");
    if (!content.exists()) {
      content = new File(checkout, "content.zip");
    }
    File properties = new File(checkout, "project.properties");
    if (content.exists()) {
      RepoProject project = new RepoProject(this, checkout.getParentFile().getName(), checkout.getName(), content, properties);
      projects.put(project.getProjectPath(), project);
    }
  }
  
  public static Repository getUninitializedRepository() {
    return new Repository(REPO_ROOT.getValue(), null);
  }
  
  public static Repository getUninitializedRepository(File repoRoot) {
    return new Repository(repoRoot, null);
  }
 
  public static Repository getRepository(File repoRoot, File tempDir) {
    Repository repo = new Repository(repoRoot, tempDir);
    
    repo.loadJarIndex();
    repo.populateRepository();
    
    return repo;
  }
  
  public static void createJarIndex(File repoRoot) {
    Repository repo = new Repository(repoRoot, null);
    JarIndex.buildJarIndexFile(repo.getJarsDir());
  }
  
  public static void printJarStats(File repoRoot) {
    Repository repo = new Repository(repoRoot, null);
    JarIndex.printJarStats(repo.getJarsDir());
  }
  
  public static void aggregateJarFiles(File repoRoot) {
    Set<String> completed = Logging.initializeResumeLogger();
    
    logger.info("--- Aggregating jar files for: " + repoRoot.getPath() + " ---");
    
    logger.info("Initializing repository...");
    Repository repo = getRepository(repoRoot, FileUtils.getTempDir());
    
    // Create the jar folder
    File jarFolder = repo.getJarsDir();
    if (!jarFolder.exists()) {
      jarFolder.mkdir();
    }
    
    // Create the name table
    Map<RepoJar, JarNamer> nameIndex = Helper.newHashMap();
    
    logger.info("Checking for partially completed aggregates...");
    // Check for any partially completed transfers
    for (File file : jarFolder.listFiles()) {
      // A tmp file is a partially completed jar
      // A info file contains the info on a partially completed jar
      if (file.isFile() && file.getName().endsWith(".info")) {
        BufferedReader br = null;;
        try {
          br = new BufferedReader(new FileReader(file));
          // The first line should contain the length
          String line = br.readLine();
          if (line == null) {
            continue;
          }
          long length = Long.parseLong(line);
          // The second line should contain the hash
          String hash = br.readLine();
          if (hash == null) {
            continue;
          }
          RepoJar jar = new RepoJar(length, hash);
          // The third line contains the name of the jar
          line = br.readLine();
          if (line == null) {
            continue;
          }
          JarNamer namer = new JarNamer(new File(jarFolder, line)); 
          // The rest of the lines contain the potential names
          for (line = br.readLine(); line != null; line = br.readLine()) {
            namer.addName(line);
          }
          namer.setInfoFile(file);
          nameIndex.put(jar, namer);
        } catch (IOException e) {
          logger.log(Level.SEVERE, "Error reading info file", e);
        } finally {
          FileUtils.close(br);
        }
      }
    }
    logger.info("Found " + nameIndex.size() + " partially completed aggregates.");
    logger.info("Found " + completed.size() + " completed projects.");
    
    logger.info("Extracting jars from " + repo.projects.size() + " projects...");
    int projectCount = completed.size();
    int totalFiles = nameIndex.size();
    int uniqueFiles = nameIndex.size();
    for (RepoProject project : repo.getProjects()) {
      projectCount++;
      if (completed.contains(project.getProjectPath())) {
        logger.info("Already completed: " + project.getProjectPath());
      } else {
        logger.info("Getting file set for: " + project.getProjectPath());
        IFileSet fileSet = project.getFileSet();
        if (fileSet == null) {
          continue;
        }
        logger.info("Extracting " + fileSet.getJarFileCount() + " jar files from project " + projectCount + " of " + repo.projects.size());
        
        for (IJarFile jar : fileSet.getJarFiles()) {
          RepoJar newJar = new RepoJar(jar.getFile());
          JarNamer namer = nameIndex.get(newJar);
          if (namer == null) {
            File tmpFile = new File(jarFolder, uniqueFiles + ".tmp");
            File infoFile = new File(jarFolder, uniqueFiles + ".info");
            FileUtils.copyFile(jar.getFile(), tmpFile);
            namer = new JarNamer(tmpFile);
            nameIndex.put(newJar, namer);
            FileWriter writer = null;
            try {
              writer = new FileWriter(infoFile);
              writer.write(newJar.getLength() + "\n");
              writer.write(newJar.getHash() + "\n");
              writer.write(tmpFile.getName());
              namer.setInfoFile(infoFile);
            } catch (IOException e) {
              logger.log(Level.SEVERE, "Unable to write info file.", e);
            } finally {
              FileUtils.close(writer);
            }
            uniqueFiles++;
          }
          totalFiles++;
          namer.addName(jar.getName());
        }
        logger.log(RESUME, project.getProjectPath());
      }
    }
      
    logger.info(totalFiles + " jars found");
    
    // Rename the jars
    logger.info("Renaming the " + uniqueFiles + " unique jar files");
    for (JarNamer namer : nameIndex.values()) {
      namer.rename();
    }
    
    logger.info("--- Done! ---");
  }
  
  public static void migrateRepository(File source, File target, Set<String> completed) {
    logger.info("--- Migrating and compressing repository from " + source.getPath() + " to " + target.getPath() + " ---");
    
    logger.info("Loading source repository...");
    Repository repo = getRepository(source, null);
    logger.info(repo.projects.size() + " projects found");
    
    logger.info("Initializing target repository...");
    if (!target.exists()) {
      target.mkdirs();
    }
    Repository targetRepo = new Repository(target, null);
    
    logger.info("Migrating " + repo.jarIndex.getIndexSize() + " jar files...");
    File jars = targetRepo.getJarsDir();
    if (!jars.exists()) {
      jars.mkdir();
    }
    for (IndexedJar jar : repo.getJarIndex().getIndexedJars()) {
      if (!completed.contains(jar.getRelativePath())) {
        jar.migrateIndexedJar(jars);
        logger.log(RESUME, jar.getRelativePath());
      }
    }
    if (repo.jarIndexFile.exists()) {
      if (!(targetRepo.jarIndexFile.exists() && completed.contains(targetRepo.jarIndexFile.getName()))) {
        FileUtils.copyFile(repo.jarIndexFile, targetRepo.jarIndexFile);
        logger.log(RESUME, targetRepo.jarIndexFile.getName());
      }
    }
    
    logger.info("Migrating " + repo.projects.size() + " projects.");
    for (RepoProject project : repo.getProjects()) {
      File targetProject = new File(target, project.getProjectPath());
      File content = project.getContent();
      File contentTarget = new File(targetProject, "content.zip");
      if (contentTarget.exists() && completed.contains(project.getProjectPath())) {
        logger.info(project.getProjectPath() + " already migrated.");
      } else {
        logger.info("Migrating " + project.getProjectPath());
        
        if (!targetProject.exists()) {
          targetProject.mkdirs();
        }
        boolean success = false;
        if (content.isFile()) {
          success = FileUtils.copyFile(content, contentTarget);
        } else {
          success = zipContent(content, contentTarget);
        }
        if (success) {
          File properties = new File(content.getParentFile(), "project.properties");
          if (properties.exists()) {
            File targetProperties = new File(targetProject, "project.properties");
            success = FileUtils.copyFile(properties, targetProperties);
          } else {
            logger.warning("No properties file for: " + project.getProjectPath());
          }
          if (success) {
            logger.log(RESUME, project.getProjectPath());
          }
        }
      }
    }
  }
  
  public static void deleteCompressedRepository(File repoRoot) {
    logger.info("--- Deleting compressed repository at: " + repoRoot.getPath() + " ---");
    
    logger.info("Initializing repository...");
    Repository repo = getRepository(repoRoot, null);
    
    logger.info("Deleting compressed content from " + repo.projects.size() + " projects...");
    for (RepoProject project : repo.getProjects()) {
      File content = project.getContent();
      if (content.isFile()) {
        logger.info("Deleting compressed content for: " + project.getProjectPath());
        content.delete();
      }
    }
    
    logger.info("--- Done! ---");
  }

  private static boolean zipContent(File input, File output) {
    ZipOutputStream zos = null;
    boolean delete = false;
    try {
      int offset = input.getPath().length() + 1;
      byte[] buff = new byte[2048];
      zos = new ZipOutputStream(new FileOutputStream(output));
      
      Deque<File> stack = Helper.newStack();
      stack.push(input);
      while (!stack.isEmpty()) {
        for (File file : stack.pop().listFiles()) {
          if (file.isDirectory()) {
            stack.add(file);
          } else {
            if (file.exists()) {
              try {
                String hash = null;
                if (file.getName().endsWith(".jar")) {
                  hash = RepoJar.getHash(file);
                }
                FileInputStream fis = new FileInputStream(file);
                try {
                  ZipEntry entry = new ZipEntry(file.getPath().substring(offset).replace('\\', '/'));
                  if (hash != null) {
                    entry.setComment(hash);
                  }
                  zos.putNextEntry(entry);
                  int read = 0;
                  while ((read = fis.read(buff)) != -1) {
                    zos.write(buff, 0, read);
                  }
                } finally {
                  FileUtils.close(fis);
                }
              } catch (IOException e) {
                logger.log(Level.SEVERE, "Unable to write zip entry for file: " + file.getName(), e);
              }
            }
          }
        }
      }
      return true;
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Unable to create content file.", e);
      delete = true;
      return false;
    } finally {
      FileUtils.close(zos);
      if (delete) {
        output.delete();
      }
    }
  }
    
  public Collection<RepoProject> getProjects() {
    return projects.values();
  }
  
  public RepoProject getProject(String projectPath) {
    return projects.get(projectPath);
  }
  
  public File getRoot() {
    return repoRoot;
  }
  
  public File getTempDir() {
    if (tempDir == null) {
      IllegalStateException e = new IllegalStateException("May not use temp dir without initializing it.");
      logger.log(Level.SEVERE, "Temp dir is null!", e);
      throw e;
    } else {
      return tempDir;
    }
  }
  
  public void cleanTempDir() {
    if (tempDir != null && tempDir.exists()) {
      cleanDir(tempDir);
    }
  }
  
  private static void cleanDir(File tempDir) {
    for (File file : tempDir.listFiles()) {
      if (file.isFile()) {
        file.delete();
      } else {
        cleanDir(file);
      }
    }
    tempDir.delete();
  }
}
