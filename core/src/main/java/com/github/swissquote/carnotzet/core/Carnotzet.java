package com.github.swissquote.carnotzet.core;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.walk;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;

import com.github.swissquote.carnotzet.core.maven.MavenDependencyResolver;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * This is the main class of this library <br>
 * It represents a full environment as a set of executable applications with their configuration.
 */
@Slf4j
public class Carnotzet {

	@Getter
	private final MavenCoordinate topLevelModuleId;

	@Getter
	private final String topLevelModuleName;

	private List<CarnotzetModule> modules;

	private final MavenDependencyResolver resolver = new MavenDependencyResolver();

	private final List<CarnotzetExtension> extensions;

	private final ResourcesManager resourceManager;

	public Carnotzet(MavenCoordinate topLevelModuleId) {
		this(topLevelModuleId, null, null, null);
	}

	/**
	 * @param topLevelModuleResourcesPath Optional : path the the top level module resources path.<br>
	 *                                    Use this if you want to use files in src/main/resources of the top module instead of the jar.
	 * @param resourcesFolder             Optional : working directory managed by carnotzet, used to resolve hierarchical configuration copy
	 *                                    <br>
	 *                                    resources files for mounting inside containers.
	 */
	public Carnotzet(MavenCoordinate topLevelModuleId, Path resourcesFolder, List<CarnotzetExtension> extensions,
			Path topLevelModuleResourcesPath) {
		log.debug("Creating new carnotzet for [{}] in path [{}]", topLevelModuleId, resourcesFolder);
		this.topLevelModuleId = topLevelModuleId;
		this.topLevelModuleName = resolver.getModuleName(topLevelModuleId);
		this.extensions = extensions;
		if (resourcesFolder == null) {
			resourcesFolder = Paths.get("/tmp/carnotzet_" + System.nanoTime());
		}
		resourcesFolder = resourcesFolder.resolve(topLevelModuleName);
		this.resourceManager = new ResourcesManager(resourcesFolder, topLevelModuleResourcesPath);
	}

	public List<CarnotzetModule> getModules() {
		if (modules == null) {
			log.debug("resolving module dependencies");
			modules = resolver.resolve(topLevelModuleId);
			log.debug("resolving module resources");
			resourceManager.resolveResources(modules, resolver::copyModuleResources);
			log.debug("Configuring individual file volumes");
			modules = configureFilesVolumes(modules);
			log.debug("Configuring env_file volumes");
			modules = configureEnvFilesVolumes(modules);

			if (extensions != null) {
				for (CarnotzetExtension feature : extensions) {
					log.debug("Extension [{}] enabled", feature.getClass().getSimpleName());
					modules = feature.apply(this);
				}
			}
		}
		return modules;
	}

	public Path getModuleResourcesPath(CarnotzetModule module) {
		return resourceManager.getModuleResourcesPath(module);
	}

	private List<CarnotzetModule> configureEnvFilesVolumes(List<CarnotzetModule> modules) {
		List<CarnotzetModule> result = new ArrayList<>();
		for (CarnotzetModule module : modules) {
			CarnotzetModule.CarnotzetModuleBuilder clone = module.toBuilder();
			clone.dockerEnvFiles(getEnvFiles(module));
			result.add(clone.build());
		}
		return result;
	}

	private Set<String> getEnvFiles(CarnotzetModule module) {
		Set<String> envFiles = new HashSet<>();
		for (CarnotzetModule otherModule : modules) {
			Path otherModuleEnvFolder = getModuleResourcesPath(otherModule).resolve(module.getName()).resolve("env");
			if (!exists(otherModuleEnvFolder)) {
				continue;
			}
			try {
				envFiles.addAll(walk(otherModuleEnvFolder).filter(p -> p.toFile().isFile()).map(Path::toString).collect(toList()));
			}
			catch (IOException e) {
				log.error(String.format("Error while reading env files for module: %s", module.getName()), e);
			}
		}
		return envFiles.isEmpty() ? null : envFiles;
	}

	private List<CarnotzetModule> configureFilesVolumes(List<CarnotzetModule> modules) {
		List<CarnotzetModule> result = new ArrayList<>();
		for (CarnotzetModule module : modules) {
			CarnotzetModule.CarnotzetModuleBuilder clone = module.toBuilder();
			clone.dockerVolumes(getFileVolumes(module));
			result.add(clone.build());
		}
		return result;
	}

	private Set<String> getFileVolumes(CarnotzetModule module) {
		Map<String, String> result = new HashMap<>();
		//look for files proposed by other modules that will need to be linked to the given module
		for (CarnotzetModule otherModule : getModules()) {
			Path toMount = getModuleResourcesPath(otherModule).resolve(module.getName()).resolve("files");
			if (!Files.exists(toMount)) {
				continue;
			}
			try {
				Files.walk(toMount).forEach((p) -> {
					if (p.toFile().isFile()) {
						result.put(p.toString(), new File(p.toString().substring(p.toString().indexOf("/files/") + 6)).getAbsolutePath());
					}
				});
			}
			catch (IOException e) {
				log.error(String.format("Error while reading env files for module:%s", module.getName()), e);
			}
		}
		return result.isEmpty() ? Collections.emptySet() : result.entrySet().stream().map(
				entry -> String.format("%s:%s", entry.getKey(), entry.getValue()))
				.collect(Collectors.toSet());
	}

	public Path getResourcesFolder() {
		return resourceManager.getResourcesRoot();
	}
}