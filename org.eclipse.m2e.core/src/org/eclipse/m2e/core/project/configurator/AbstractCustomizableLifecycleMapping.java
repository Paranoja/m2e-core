/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.project.configurator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.apache.maven.plugin.MojoExecution;

import org.eclipse.m2e.core.internal.lifecycle.LifecycleMappingConfigurationException;
import org.eclipse.m2e.core.internal.lifecycle.LifecycleMappingFactory;
import org.eclipse.m2e.core.internal.lifecycle.model.PluginExecutionMetadata;
import org.eclipse.m2e.core.project.IMavenProjectFacade;


/**
 * Abstract base class for customizable lifecycle mappings
 * 
 * @author igor
 */
public abstract class AbstractCustomizableLifecycleMapping extends AbstractLifecycleMapping {
  private static Logger log = LoggerFactory.getLogger(AbstractCustomizableLifecycleMapping.class);

  public Map<MojoExecutionKey, List<AbstractBuildParticipant>> getBuildParticipants(IMavenProjectFacade projectFacade,
      IProgressMonitor monitor) throws CoreException {
    log.debug("Build participants for {}", projectFacade.getMavenProject());
    Map<MojoExecutionKey, List<AbstractBuildParticipant>> result = new LinkedHashMap<MojoExecutionKey, List<AbstractBuildParticipant>>();

    Map<MojoExecutionKey, List<PluginExecutionMetadata>> mapping = projectFacade.getMojoExecutionMapping();
    Map<String, AbstractProjectConfigurator> configurators = getProjectConfigurators(projectFacade);

    for(Map.Entry<MojoExecutionKey, List<PluginExecutionMetadata>> entry : mapping.entrySet()) {
      MojoExecutionKey executionKey = entry.getKey();
      log.debug("Mojo execution key: {}", executionKey);
      MojoExecution mojoExecution = projectFacade.getMojoExecution(executionKey, monitor);
      List<PluginExecutionMetadata> executionMetadatas = entry.getValue();
      List<AbstractBuildParticipant> executionMappings = new ArrayList<AbstractBuildParticipant>();
      if(executionMetadatas != null) {
        for(PluginExecutionMetadata executionMetadata : executionMetadatas) {
          log.debug("\tAction: {}", executionMetadata.getAction());
          switch(executionMetadata.getAction()) {
            case execute:
              executionMappings.add(LifecycleMappingFactory.createMojoExecutionBuildParicipant(projectFacade,
                  mojoExecution, executionMetadata));
              break;
            case configurator:
              String configuratorId = LifecycleMappingFactory.getProjectConfiguratorId(executionMetadata);
              log.debug("\t\tProject configurator id: {}", configuratorId);
              AbstractProjectConfigurator configurator = configurators.get(configuratorId);
              AbstractBuildParticipant buildParticipant = configurator.getBuildParticipant(projectFacade,
                  mojoExecution, executionMetadata);
              if(buildParticipant != null) {
                log.debug("\t\tBuild participant: {}", buildParticipant.getClass().getName());
                executionMappings.add(buildParticipant);
              }
              break;
            case ignore:
            case error:
              break;
            default:
              throw new IllegalArgumentException("Missing handling for action=" + executionMetadata.getAction());
          }
        }
      }

      result.put(executionKey, executionMappings);
    }

    return result;
  }

  public List<AbstractProjectConfigurator> getProjectConfigurators(IMavenProjectFacade projectFacade,
      IProgressMonitor monitor) {
    return new ArrayList<AbstractProjectConfigurator>(getProjectConfigurators(projectFacade).values());
  }

  private Map<String, AbstractProjectConfigurator> getProjectConfigurators(IMavenProjectFacade projectFacade) {
    return LifecycleMappingFactory.getProjectConfigurators(projectFacade);
  }

  public boolean hasLifecycleMappingChanged(IMavenProjectFacade oldFacade, IMavenProjectFacade newFacade,
      IProgressMonitor monitor) {
    if(!getId().equals(newFacade.getLifecycleMappingId())) {
      throw new IllegalArgumentException();
    }

    if(oldFacade == null || !getId().equals(oldFacade.getLifecycleMappingId())) {
      return true;
    }

    Map<MojoExecutionKey, List<PluginExecutionMetadata>> oldMappings = oldFacade.getMojoExecutionMapping();

    for(Map.Entry<MojoExecutionKey, List<PluginExecutionMetadata>> entry : newFacade.getMojoExecutionMapping()
        .entrySet()) {
      List<PluginExecutionMetadata> metadatas = entry.getValue();
      List<PluginExecutionMetadata> oldMetadatas = oldMappings.get(entry.getKey());
      if(metadatas == null || metadatas.isEmpty()) {
        if(oldMetadatas != null && !oldMetadatas.isEmpty()) {
          return true; // different
        }
        continue; // mapping is null/empty and did not change
      }
      if(oldMetadatas == null || oldMetadatas.isEmpty()) {
        return true;
      }
      if(metadatas.size() != oldMetadatas.size()) {
        return true;
      }
      for(int i = 0; i < metadatas.size(); i++ ) {
        PluginExecutionMetadata metadata = metadatas.get(i);
        PluginExecutionMetadata oldMetadata = oldMetadatas.get(i);
        if(metadata == null) {
          if(oldMetadata != null) {
            return true;
          }
          continue;
        }
        if(oldMetadata == null) {
          return true;
        }
        if(metadata.getAction() != oldMetadata.getAction()) {
          return true;
        }
        switch(metadata.getAction()) {
          case ignore:
          case execute:
            continue;
          case error:
            // TODO verify error message did not change...
            continue;
          case configurator:
            String configuratorId = LifecycleMappingFactory.getProjectConfiguratorId(metadata);
            String oldConfiguratorId = LifecycleMappingFactory.getProjectConfiguratorId(oldMetadata);
            if(!eq(configuratorId, oldConfiguratorId)) {
              return true;
            }
            try {
              AbstractProjectConfigurator configurator = LifecycleMappingFactory.createProjectConfigurator(metadata);
              if(configurator.hasConfigurationChanged(oldFacade, newFacade, entry.getKey(), monitor)) {
                return true;
              }
            } catch(LifecycleMappingConfigurationException e) {
              // installation problem/misconfiguration
            }
            continue;
        }
      }
    }

    return false;
  }

  private static <T> boolean eq(T a, T b) {
    return a != null ? a.equals(b) : b == null;
  }

}
