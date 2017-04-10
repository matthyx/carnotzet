package com.github.swissquote.carnotzet.runtime.docker.compose;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.swissquote.carnotzet.core.Carnotzet;
import com.github.swissquote.carnotzet.core.CarnotzetModule;
import com.github.swissquote.carnotzet.core.runtime.CommandRunner;
import com.github.swissquote.carnotzet.core.runtime.api.Container;
import com.github.swissquote.carnotzet.core.runtime.api.ContainerOrchestrationRuntime;
import com.github.swissquote.carnotzet.core.runtime.log.LogListener;
import com.google.common.base.Strings;
import com.google.common.io.Files;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DockerComposeRuntime implements ContainerOrchestrationRuntime {

	private final Carnotzet carnotzet;

	private final DockerLogManager logManager;

	public DockerComposeRuntime(Carnotzet carnotzet) {
		this.carnotzet = carnotzet;
		this.logManager = new DockerLogManager();

	}

	private void computeDockerComposeFile() {
		log.debug(String.format("Building docker-compose.yml for [%s]", carnotzet.getTopLevelModuleId()));

		Map<String, Service> services = new HashMap<>();
		List<CarnotzetModule> modules = carnotzet.getModules();
		for (CarnotzetModule module : modules) {
			if (module.getImageName() == null) {
				log.debug("Module [{}] has no docker image", module.getName());
				continue;
			}
			Service.ServiceBuilder serviceBuilder = Service.builder();
			String moduleName = module.getName();

			//serviceBuilder.container_name(module.getContainerName());
			serviceBuilder.image(module.getImageName());
			serviceBuilder.volumes(module.getDockerVolumes());
			serviceBuilder.entrypoint(module.getDockerEntrypoint());
			serviceBuilder.env_file(module.getDockerEnvFiles());

			Map<String, ContainerNetwork> networks = new HashMap<>();
			Set<String> networkAliases = new HashSet<>();
			if (module.getProperties().containsKey("network.aliases")) {
				networkAliases.addAll(Arrays.stream(module.getProperties().get("network.aliases")
						.split(","))
						.map(String::trim)
						.collect(Collectors.toList()));
			}
			networkAliases.add(module.getShortImageName() + ".docker");
			networkAliases.add(module.getContainerName() + "." + module.getShortImageName() + ".docker");
			ContainerNetwork network = ContainerNetwork.builder().aliases(networkAliases).build();
			networks.put("carnotzet", network);
			serviceBuilder.networks(networks);

			services.put(moduleName, serviceBuilder.build());
		}

		Network network = Network.builder().driver("bridge").build();
		Map<String, Network> networks = new HashMap<>();
		networks.put("carnotzet", network);

		DockerCompose compose = DockerCompose.builder().version("2").services(services).networks(networks).build();
		DockerComposeGenerator generator = new DockerComposeGenerator(compose);
		try {
			Files.write(generator.generateDockerComposeFile().getBytes(), carnotzet.getResourcesFolder().resolve("docker-compose.yml").toFile());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to write docker-compose.yml", e);
		}
		log.debug(String.format("End build compose file for module %s", carnotzet.getTopLevelModuleId()));
	}

	@Override
	public void start() {
		log.debug("Forcing update of docker-compose.yml before start");
		computeDockerComposeFile();
		Instant start = Instant.now();
		runCommand("docker-compose", "up", "-d");
		ensureNetworkCommunicationIsPossible();
		logManager.ensureCapturingLogs(start, getContainers());
	}

	@Override
	public void start(String service) {
		log.debug("Forcing update of docker-compose.yml before start");
		computeDockerComposeFile();
		Instant start = Instant.now();
		runCommand("docker-compose", "up", "-d", service);
		ensureNetworkCommunicationIsPossible();
		logManager.ensureCapturingLogs(start, Collections.singletonList(getContainer(service)));
	}

	private void ensureNetworkCommunicationIsPossible() {
		String buildContainerId =
				runCommandAndCaptureOutput("/bin/sh", "-c", "docker ps | grep $(hostname) | grep -v k8s_POD | cut -d ' ' -f 1");

		if (Strings.isNullOrEmpty(buildContainerId)) {
			// we are probably not running inside a container, networking should be fine
			return;
		}

		log.debug("Execution from inside a container detected! Attempting to configure container networking to allow communication.");

		String networkMode =
				runCommandAndCaptureOutput("/bin/sh", "-c", "docker inspect -f '{{.HostConfig.NetworkMode}}' " + buildContainerId);

		String containerToConnect = buildContainerId;

		// shared network stack
		if (networkMode.startsWith("container:")) {
			containerToConnect = networkMode.replace("container:", "");
			log.debug("Detected a shared container network stack.");
		}
		log.debug("attaching container [" + containerToConnect + "] to network [" + getDockerNetworkName() + "]");
		runCommand("/bin/sh", "-c", "docker network connect " + getDockerNetworkName() + " " + containerToConnect);
	}

	private String getDockerComposeProjectName() {
		return normalizeDockerComposeProjectName(carnotzet.getTopLevelModuleName());
	}

	private String getDockerNetworkName() {
		return getDockerComposeProjectName() + "_carnotzet";
	}

	// normalize docker compose project name the same way docker-compose does (see https://github.com/docker/compose/tree/master/compose)
	private String normalizeDockerComposeProjectName(String moduleName) {
		return moduleName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
	}

	@Override
	public void stop() {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "stop");
		// networks are created on demand and it's very fast, deleting the network upon stop helps avoiding sub-network starvation
		// when using a lot of docker networks
		runCommandAndCaptureOutput("docker", "network", "rm", getDockerNetworkName());
	}

	@Override
	public void stop(String service) {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "stop", service);
	}

	@Override
	public void status() {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "ps");
	}

	@Override
	public void clean() {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "rm", "-f");
	}

	@Override
	public void shell(Container container) {
		ensureDockerComposeFileIsPresent();
		try {
			Process process = new ProcessBuilder("docker", "exec", "-it", container.getId(), "/bin/bash").inheritIO().start();
			process.waitFor();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot execute docker exec", ex);
		}
		catch (InterruptedException e) {
			//exit
		}
	}

	@Override
	public void pull(String service) {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "pull", service);
	}

	@Override
	public void pull() {
		ensureDockerComposeFileIsPresent();
		runCommand("docker-compose", "pull");
	}

	@Override
	public List<Container> getContainers() {
		String commandOutput = runCommandAndCaptureOutput("docker-compose", "ps", "-q").replaceAll("\n", " ");
		log.debug("docker-compose ps output : " + commandOutput);
		if (commandOutput.trim().isEmpty()) {
			return Collections.emptyList();
		}
		List<String> args = new ArrayList<>(Arrays.asList("docker", "inspect", "-f", "{{ index .Id}}:"
				+ "{{ index .Config.Labels \"com.docker.compose.service\" }}:"
				+ "{{ index .State.Running}}:"
				+ "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}:"
		));
		args.addAll(Arrays.asList(commandOutput.split(" ")));
		commandOutput = runCommandAndCaptureOutput(args.toArray(new String[args.size()]));
		log.debug("docker inspect output : " + commandOutput);
		log.debug("split docker inspect output : " + Arrays.asList(commandOutput.split("\n")));
		return Arrays.stream(commandOutput.split("\n"))
				.filter(desc -> !desc.trim().isEmpty())
				.map(desc -> desc.split(":"))
				.map(parts -> new Container(parts[0], parts[1], parts[2].equals("true"), parts.length > 3 ? parts[3] : null))
				.sorted(Comparator.comparing(Container::getServiceName))
				.collect(Collectors.toList());

	}

	@Override
	public Container getContainer(String serviceName) {
		return getContainers().stream().filter(c -> c.getServiceName().equals(serviceName)).findFirst().orElse(null);
	}

	@Override
	public void registerLogListener(LogListener listener) {
		ensureDockerComposeFileIsPresent();
		logManager.registerLogListener(listener, getContainers());
	}

	@Override
	public boolean isRunning() {
		ensureDockerComposeFileIsPresent();
		return getContainers().stream().anyMatch(Container::isRunning);
	}

	private int runCommand(String... command) {
		return CommandRunner.runCommand(carnotzet.getResourcesFolder().toFile(), command);
	}

	private String runCommandAndCaptureOutput(String... command) {
		return CommandRunner.runCommandAndCaptureOutput(carnotzet.getResourcesFolder().toFile(), command);
	}

	private boolean dockerComposeFileExists() {
		return java.nio.file.Files.exists(carnotzet.getResourcesFolder().resolve("docker-compose.yml"));
	}

	private void ensureDockerComposeFileIsPresent() {
		if (dockerComposeFileExists()) {
			log.debug("Using existing docker-compose.yml");
			return;
		}
		log.debug("docker-compose.yml not found");
		computeDockerComposeFile();
	}

	public void clean(String service) {
		runCommand("docker-compose", "rm", "-f", service);
	}
}