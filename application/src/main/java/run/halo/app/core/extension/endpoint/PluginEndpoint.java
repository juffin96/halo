package run.halo.app.core.extension.endpoint;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static java.util.Comparator.comparing;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;
import static org.springframework.boot.convert.ApplicationConversionService.getSharedInstance;
import static org.springframework.core.io.buffer.DataBufferUtils.write;
import static org.springframework.web.reactive.function.server.RequestPredicates.contentType;
import static run.halo.app.extension.ListResult.generateGenericClass;
import static run.halo.app.extension.router.QueryParamBuildUtil.buildParametersFromType;
import static run.halo.app.extension.router.selector.SelectorUtil.labelAndFieldSelectorToPredicate;
import static run.halo.app.infra.utils.FileUtils.deleteFileSilently;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginState;
import org.reactivestreams.Publisher;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Setting;
import run.halo.app.core.extension.service.PluginService;
import run.halo.app.core.extension.theme.SettingUtils;
import run.halo.app.extension.Comparators;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.IListRequest.QueryListRequest;
import run.halo.app.infra.ReactiveUrlDataBufferFetcher;
import run.halo.app.plugin.PluginNotFoundException;

@Slf4j
@Component
@AllArgsConstructor
public class PluginEndpoint implements CustomEndpoint {
    private static final CacheControl MAX_CACHE_CONTROL = CacheControl.maxAge(365, TimeUnit.DAYS);

    private final ReactiveExtensionClient client;

    private final PluginService pluginService;

    private final ReactiveUrlDataBufferFetcher reactiveUrlDataBufferFetcher;

    private final Scheduler scheduler = Schedulers.boundedElastic();

    private final BufferedPluginBundleResource bufferedPluginBundleResource;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.console.halo.run/v1alpha1/Plugin";
        return SpringdocRouteBuilder.route()
            .POST("plugins/install", contentType(MediaType.MULTIPART_FORM_DATA),
                this::install, builder -> builder.operationId("InstallPlugin")
                    .description("Install a plugin by uploading a Jar file.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.MULTIPART_FORM_DATA_VALUE)
                            .schema(schemaBuilder().implementation(InstallRequest.class))
                        ))
                    .response(responseBuilder().implementation(Plugin.class))
            )
            .POST("plugins/-/install-from-uri", this::installFromUri,
                builder -> builder.operationId("InstallPluginFromUri")
                    .description("Install a plugin from uri.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder()
                                .implementation(InstallFromUriRequest.class))
                        ))
                    .response(responseBuilder()
                        .implementation(Plugin.class))
            )
            .POST("plugins/{name}/upgrade-from-uri", this::upgradeFromUri,
                builder -> builder.operationId("UpgradePluginFromUri")
                    .description("Upgrade a plugin from uri.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .in(ParameterIn.PATH)
                        .name("name")
                        .required(true)
                    )
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder()
                                .implementation(UpgradeFromUriRequest.class))
                        ))
                    .response(responseBuilder()
                        .implementation(Plugin.class))
            )
            .POST("plugins/{name}/upgrade", contentType(MediaType.MULTIPART_FORM_DATA),
                this::upgrade, builder -> builder.operationId("UpgradePlugin")
                    .description("Upgrade a plugin by uploading a Jar file")
                    .tag(tag)
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder().mediaType(MediaType.MULTIPART_FORM_DATA_VALUE)
                            .schema(schemaBuilder().implementation(InstallRequest.class))))
            )
            .PUT("plugins/{name}/config", this::updatePluginConfig,
                builder -> builder.operationId("updatePluginConfig")
                    .description("Update the configMap of plugin setting.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder().implementation(ConfigMap.class))))
                    .response(responseBuilder()
                        .implementation(ConfigMap.class))
            )
            .PUT("plugins/{name}/reset-config", this::resetSettingConfig,
                builder -> builder.operationId("ResetPluginConfig")
                    .description("Reset the configMap of plugin setting.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .response(responseBuilder()
                        .implementation(ConfigMap.class))
            )
            .PUT("plugins/{name}/reload", this::reload,
                builder -> builder.operationId("reloadPlugin")
                    .description("Reload a plugin by name.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .response(responseBuilder()
                        .implementation(Plugin.class))
            )
            .PUT("plugins/{name}/plugin-state", this::changePluginRunningState,
                builder -> builder.operationId("ChangePluginRunningState")
                    .description("Change the running state of a plugin by name.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder()
                                .implementation(RunningStateRequest.class))
                        )
                    )
                    .response(responseBuilder()
                        .implementation(Plugin.class))
            )
            .GET("plugins", this::list, builder -> {
                builder.operationId("ListPlugins")
                    .tag(tag)
                    .description("List plugins using query criteria and sort params")
                    .response(responseBuilder().implementation(generateGenericClass(Plugin.class)));
                buildParametersFromType(builder, ListRequest.class);
            })
            .GET("plugins/{name}/setting", this::fetchPluginSetting,
                builder -> builder.operationId("fetchPluginSetting")
                    .description("Fetch setting of plugin.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .response(responseBuilder()
                        .implementation(Setting.class))
            )
            .GET("plugins/{name}/config", this::fetchPluginConfig,
                builder -> builder.operationId("fetchPluginConfig")
                    .description("Fetch configMap of plugin by configured configMapName.")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class)
                    )
                    .response(responseBuilder()
                        .implementation(ConfigMap.class))
            )
            .GET("plugin-presets", this::listPresets,
                builder -> builder.operationId("ListPluginPresets")
                    .description("List all plugin presets in the system.")
                    .tag(tag)
                    .response(responseBuilder().implementationArray(Plugin.class))
            )
            .GET("plugins/-/bundle.js", this::fetchJsBundle,
                builder -> builder.operationId("fetchJsBundle")
                    .description("Merge all JS bundles of enabled plugins into one.")
                    .tag(tag)
                    .response(responseBuilder().implementation(String.class))
            )
            .GET("plugins/-/bundle.css", this::fetchCssBundle,
                builder -> builder.operationId("fetchCssBundle")
                    .description("Merge all CSS bundles of enabled plugins into one.")
                    .tag(tag)
                    .response(responseBuilder().implementation(String.class))
            )
            .build();
    }

    Mono<ServerResponse> changePluginRunningState(ServerRequest request) {
        final var name = request.pathVariable("name");
        return request.bodyToMono(RunningStateRequest.class)
            .flatMap(runningState -> {
                final var enable = runningState.isEnable();
                return client.get(Plugin.class, name)
                    .flatMap(plugin -> {
                        plugin.getSpec().setEnabled(enable);
                        return client.update(plugin);
                    })
                    .flatMap(plugin -> {
                        if (runningState.isAsync()) {
                            return Mono.just(plugin);
                        }
                        return waitForPluginToMeetExpectedState(name, p -> {
                            // when enabled = true,excepted phase = started || failed
                            // when enabled = false,excepted phase = !started
                            var phase = p.statusNonNull().getPhase();
                            if (enable) {
                                return PluginState.STARTED.equals(phase)
                                    || PluginState.FAILED.equals(phase);
                            }
                            return !PluginState.STARTED.equals(phase);
                        });
                    });
            })
            .flatMap(plugin -> ServerResponse.ok().bodyValue(plugin));
    }

    Mono<Plugin> waitForPluginToMeetExpectedState(String name, Predicate<Plugin> predicate) {
        return Mono.defer(() -> client.get(Plugin.class, name)
                .map(plugin -> {
                    if (predicate.test(plugin)) {
                        return plugin;
                    }
                    throw new IllegalStateException("Plugin " + name + " is not in expected state");
                })
            )
            .retryWhen(Retry.backoff(10, Duration.ofMillis(100))
                .filter(IllegalStateException.class::isInstance)
            );
    }

    @Data
    @Schema(name = "PluginRunningStateRequest")
    static class RunningStateRequest {
        private boolean enable;
        private boolean async;
    }

    private Mono<ServerResponse> fetchJsBundle(ServerRequest request) {
        Optional<String> versionOption = request.queryParam("v");
        return versionOption.map(s ->
                Mono.defer(() -> bufferedPluginBundleResource
                    .getJsBundle(s, pluginService::uglifyJsBundle)
                ).flatMap(fsRes -> {
                    var bodyBuilder = ServerResponse.ok()
                        .cacheControl(MAX_CACHE_CONTROL)
                        .contentType(MediaType.valueOf("text/javascript"));
                    try {
                        Instant lastModified = Instant.ofEpochMilli(fsRes.lastModified());
                        return request.checkNotModified(lastModified)
                            .switchIfEmpty(Mono.defer(() ->
                                bodyBuilder.lastModified(lastModified)
                                    .body(BodyInserters.fromResource(fsRes)))
                            );
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                })
            )
            .orElseGet(() -> pluginService.generateJsBundleVersion()
                .flatMap(v -> ServerResponse
                    .temporaryRedirect(buildJsBundleUri("js", v))
                    .build()
                )
            );
    }

    private Mono<ServerResponse> fetchCssBundle(ServerRequest request) {
        Optional<String> versionOption = request.queryParam("v");
        return versionOption.map(s ->
                Mono.defer(() -> bufferedPluginBundleResource.getCssBundle(s,
                    pluginService::uglifyCssBundle)
                ).flatMap(fsRes -> {
                    var bodyBuilder = ServerResponse.ok()
                        .cacheControl(MAX_CACHE_CONTROL)
                        .contentType(MediaType.valueOf("text/css"));
                    try {
                        Instant lastModified = Instant.ofEpochMilli(fsRes.lastModified());
                        return request.checkNotModified(lastModified)
                            .switchIfEmpty(Mono.defer(() ->
                                bodyBuilder.lastModified(lastModified)
                                    .body(BodyInserters.fromResource(fsRes)))
                            );
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                })
            )
            .orElseGet(() -> pluginService.generateJsBundleVersion()
                .flatMap(v -> ServerResponse
                    .temporaryRedirect(buildJsBundleUri("css", v))
                    .build()
                )
            );
    }

    URI buildJsBundleUri(String type, String version) {
        return URI.create(
            "/apis/api.console.halo.run/v1alpha1/plugins/-/bundle." + type + "?v=" + version);
    }

    private Mono<ServerResponse> upgradeFromUri(ServerRequest request) {
        var name = request.pathVariable("name");
        var content = request.bodyToMono(UpgradeFromUriRequest.class)
            .map(UpgradeFromUriRequest::uri)
            .flatMapMany(reactiveUrlDataBufferFetcher::fetch);

        return Mono.usingWhen(
                writeToTempFile(content),
                path -> pluginService.upgrade(name, path),
                this::deleteFileIfExists)
            .flatMap(upgradedPlugin -> ServerResponse.ok().bodyValue(upgradedPlugin));
    }

    private Mono<ServerResponse> installFromUri(ServerRequest request) {
        var content = request.bodyToMono(InstallFromUriRequest.class)
            .map(InstallFromUriRequest::uri)
            .flatMapMany(reactiveUrlDataBufferFetcher::fetch);

        return Mono.usingWhen(
                writeToTempFile(content),
                pluginService::install,
                this::deleteFileIfExists
            )
            .flatMap(newPlugin -> ServerResponse.ok().bodyValue(newPlugin));
    }

    public record InstallFromUriRequest(@Schema(requiredMode = REQUIRED) URI uri) {
    }

    public record UpgradeFromUriRequest(@Schema(requiredMode = REQUIRED) URI uri) {
    }

    private Mono<ServerResponse> reload(ServerRequest serverRequest) {
        var name = serverRequest.pathVariable("name");
        return ServerResponse.ok().body(pluginService.reload(name), Plugin.class);
    }

    private Mono<ServerResponse> listPresets(ServerRequest request) {
        return ServerResponse.ok().body(pluginService.getPresets(), Plugin.class);
    }

    private Mono<ServerResponse> fetchPluginConfig(ServerRequest request) {
        final var name = request.pathVariable("name");
        return client.fetch(Plugin.class, name)
            .mapNotNull(plugin -> plugin.getSpec().getConfigMapName())
            .flatMap(configMapName -> client.fetch(ConfigMap.class, configMapName))
            .flatMap(configMap -> ServerResponse.ok().bodyValue(configMap));
    }

    private Mono<ServerResponse> fetchPluginSetting(ServerRequest request) {
        final var name = request.pathVariable("name");
        return client.fetch(Plugin.class, name)
            .mapNotNull(plugin -> plugin.getSpec().getSettingName())
            .flatMap(settingName -> client.fetch(Setting.class, settingName))
            .flatMap(setting -> ServerResponse.ok().bodyValue(setting));
    }

    private Mono<ServerResponse> updatePluginConfig(ServerRequest request) {
        final var pluginName = request.pathVariable("name");
        return client.fetch(Plugin.class, pluginName)
            .doOnNext(plugin -> {
                String configMapName = plugin.getSpec().getConfigMapName();
                if (!StringUtils.hasText(configMapName)) {
                    throw new ServerWebInputException(
                        "Unable to complete the request because the plugin configMapName is blank");
                }
            })
            .flatMap(plugin -> {
                final String configMapName = plugin.getSpec().getConfigMapName();
                return request.bodyToMono(ConfigMap.class)
                    .doOnNext(configMapToUpdate -> {
                        var configMapNameToUpdate = configMapToUpdate.getMetadata().getName();
                        if (!configMapName.equals(configMapNameToUpdate)) {
                            throw new ServerWebInputException(
                                "The name from the request body does not match the plugin "
                                    + "configMapName name.");
                        }
                    })
                    .flatMap(configMapToUpdate -> client.fetch(ConfigMap.class, configMapName)
                        .map(persisted -> {
                            configMapToUpdate.getMetadata()
                                .setVersion(persisted.getMetadata().getVersion());
                            return configMapToUpdate;
                        })
                        .switchIfEmpty(client.create(configMapToUpdate))
                    )
                    .flatMap(client::update)
                    .retryWhen(Retry.backoff(5, Duration.ofMillis(300))
                        .filter(OptimisticLockingFailureException.class::isInstance)
                    );
            })
            .flatMap(configMap -> ServerResponse.ok().bodyValue(configMap));
    }

    private Mono<ServerResponse> resetSettingConfig(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(Plugin.class, name)
            .filter(plugin -> StringUtils.hasText(plugin.getSpec().getSettingName()))
            .flatMap(plugin -> {
                String configMapName = plugin.getSpec().getConfigMapName();
                String settingName = plugin.getSpec().getSettingName();
                return client.fetch(Setting.class, settingName)
                    .map(SettingUtils::settingDefinedDefaultValueMap)
                    .flatMap(data -> updateConfigMapData(configMapName, data));
            })
            .flatMap(configMap -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(configMap));
    }

    private Mono<ConfigMap> updateConfigMapData(String configMapName, Map<String, String> data) {
        return client.fetch(ConfigMap.class, configMapName)
            .flatMap(configMap -> {
                configMap.setData(data);
                return client.update(configMap);
            })
            .retryWhen(Retry.fixedDelay(10, Duration.ofMillis(100))
                .filter(t -> t instanceof OptimisticLockingFailureException));
    }


    private Mono<ServerResponse> install(ServerRequest request) {
        return request.multipartData()
            .map(InstallRequest::new)
            .flatMap(installRequest -> installRequest.getSource()
                .flatMap(source -> {
                    if (InstallSource.FILE.equals(source)) {
                        return installFromFile(installRequest.getFile(), pluginService::install);
                    }
                    if (InstallSource.PRESET.equals(source)) {
                        return installFromPreset(installRequest.getPresetName(),
                            pluginService::install);
                    }
                    return Mono.error(
                        new UnsupportedOperationException("Unsupported install source " + source));
                }))
            .flatMap(plugin -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(plugin));
    }

    private Mono<ServerResponse> upgrade(ServerRequest request) {
        var pluginName = request.pathVariable("name");
        return request.multipartData()
            .map(InstallRequest::new)
            .flatMap(installRequest -> installRequest.getSource()
                .flatMap(source -> {
                    if (InstallSource.FILE.equals(source)) {
                        return installFromFile(installRequest.getFile(),
                            path -> pluginService.upgrade(pluginName, path));
                    }
                    if (InstallSource.PRESET.equals(source)) {
                        return installFromPreset(installRequest.getPresetName(),
                            path -> pluginService.upgrade(pluginName, path));
                    }
                    return Mono.error(
                        new UnsupportedOperationException("Unsupported install source " + source));
                }))
            .flatMap(upgradedPlugin -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upgradedPlugin));
    }

    private Mono<Plugin> installFromFile(FilePart filePart,
        Function<Path, Mono<Plugin>> resourceClosure) {
        return Mono.usingWhen(
            writeToTempFile(filePart.content()),
            resourceClosure,
            this::deleteFileIfExists);
    }

    private Mono<Plugin> installFromPreset(Mono<String> presetNameMono,
        Function<Path, Mono<Plugin>> resourceClosure) {
        return presetNameMono.flatMap(pluginService::getPreset)
            .switchIfEmpty(
                Mono.error(() -> new PluginNotFoundException("Plugin preset was not found.")))
            .map(pluginPreset -> pluginPreset.getStatus().getLoadLocation())
            .map(Path::of)
            .flatMap(resourceClosure);
    }

    public static class ListRequest extends QueryListRequest {

        private final ServerWebExchange exchange;

        public ListRequest(ServerRequest request) {
            super(request.queryParams());
            this.exchange = request.exchange();
        }

        @Schema(name = "keyword", description = "Keyword of plugin name or description")
        public String getKeyword() {
            return queryParams.getFirst("keyword");
        }

        @Schema(name = "enabled", description = "Whether the plugin is enabled")
        public Boolean getEnabled() {
            var enabled = queryParams.getFirst("enabled");
            return enabled == null ? null : getSharedInstance().convert(enabled, Boolean.class);
        }

        @ArraySchema(uniqueItems = true,
            arraySchema = @Schema(name = "sort",
                description = "Sort property and direction of the list result. Supported fields: "
                    + "creationTimestamp"),
            schema = @Schema(description = "like field,asc or field,desc",
                implementation = String.class,
                example = "creationTimestamp,desc"))
        public Sort getSort() {
            return SortResolver.defaultInstance.resolve(exchange);
        }

        public Predicate<Plugin> toPredicate() {
            Predicate<Plugin> displayNamePredicate = plugin -> {
                var keyword = getKeyword();
                if (!StringUtils.hasText(keyword)) {
                    return true;
                }
                var displayName = plugin.getSpec().getDisplayName();
                if (!StringUtils.hasText(displayName)) {
                    return false;
                }
                return displayName.toLowerCase().contains(keyword.trim().toLowerCase());
            };
            Predicate<Plugin> descriptionPredicate = plugin -> {
                var keyword = getKeyword();
                if (!StringUtils.hasText(keyword)) {
                    return true;
                }
                var description = plugin.getSpec().getDescription();
                if (!StringUtils.hasText(description)) {
                    return false;
                }
                return description.toLowerCase().contains(keyword.trim().toLowerCase());
            };
            Predicate<Plugin> enablePredicate = plugin -> {
                var enabled = getEnabled();
                if (enabled == null) {
                    return true;
                }
                return Objects.equals(enabled, plugin.getSpec().getEnabled());
            };
            return displayNamePredicate.or(descriptionPredicate)
                .and(enablePredicate)
                .and(labelAndFieldSelectorToPredicate(getLabelSelector(), getFieldSelector()));
        }

        public Comparator<Plugin> toComparator() {
            var sort = getSort();
            var ctOrder = sort.getOrderFor("creationTimestamp");
            List<Comparator<Plugin>> comparators = new ArrayList<>();
            if (ctOrder != null) {
                Comparator<Plugin> comparator =
                    comparing(plugin -> plugin.getMetadata().getCreationTimestamp());
                if (ctOrder.isDescending()) {
                    comparator = comparator.reversed();
                }
                comparators.add(comparator);
            }
            comparators.add(Comparators.compareCreationTimestamp(false));
            comparators.add(Comparators.compareName(true));
            return comparators.stream()
                .reduce(Comparator::thenComparing)
                .orElse(null);
        }
    }

    Mono<ServerResponse> list(ServerRequest request) {
        return Mono.just(request)
            .map(ListRequest::new)
            .flatMap(listRequest -> {
                var predicate = listRequest.toPredicate();
                var comparator = listRequest.toComparator();
                return client.list(Plugin.class,
                    predicate,
                    comparator,
                    listRequest.getPage(),
                    listRequest.getSize());
            })
            .flatMap(listResult -> ServerResponse.ok().bodyValue(listResult));
    }

    @Schema(name = "PluginInstallRequest")
    public static class InstallRequest {

        private final MultiValueMap<String, Part> multipartData;

        public InstallRequest(MultiValueMap<String, Part> multipartData) {
            this.multipartData = multipartData;
        }

        @Schema(requiredMode = NOT_REQUIRED, description = "Plugin Jar file.")
        public FilePart getFile() {
            var part = multipartData.getFirst("file");
            if (part == null) {
                throw new ServerWebInputException("Form field file is required");
            }
            if (!(part instanceof FilePart file)) {
                throw new ServerWebInputException("Invalid parameter of file");
            }
            if (!Paths.get(file.filename()).toString().endsWith(".jar")) {
                throw new ServerWebInputException("Invalid file type, only jar is supported");
            }
            return file;
        }

        @Schema(requiredMode = NOT_REQUIRED,
            description = "Plugin preset name. We will find the plugin from plugin presets")
        public Mono<String> getPresetName() {
            var part = multipartData.getFirst("presetName");
            if (part == null) {
                return Mono.error(new ServerWebInputException(
                    "Form field presetName is required."));
            }
            if (!(part instanceof FormFieldPart presetName)) {
                return Mono.error(new ServerWebInputException(
                    "Invalid format of presetName field, string required"));
            }
            if (!StringUtils.hasText(presetName.value())) {
                return Mono.error(new ServerWebInputException("presetName must not be blank"));
            }
            return Mono.just(presetName.value());
        }

        @Schema(requiredMode = NOT_REQUIRED, description = "Install source. Default is file.")
        public Mono<InstallSource> getSource() {
            var part = multipartData.getFirst("source");
            if (part == null) {
                return Mono.just(InstallSource.FILE);
            }
            if (!(part instanceof FormFieldPart sourcePart)) {
                return Mono.error(new ServerWebInputException(
                    "Invalid format of source field, string required."));
            }
            var installSource = InstallSource.valueOf(sourcePart.value().toUpperCase());
            return Mono.just(installSource);
        }
    }

    public enum InstallSource {
        FILE,
        PRESET,
        URL
    }

    Mono<Void> deleteFileIfExists(Path path) {
        return deleteFileSilently(path, this.scheduler).then();
    }

    private Mono<Path> writeToTempFile(Publisher<DataBuffer> content) {
        return Mono.fromCallable(() -> Files.createTempFile("halo-plugin-", ".jar"))
            .flatMap(path -> write(content, path).thenReturn(path))
            .subscribeOn(this.scheduler);
    }

    @Component
    static class BufferedPluginBundleResource implements DisposableBean {

        private final AtomicReference<FileSystemResource> jsBundle = new AtomicReference<>();
        private final AtomicReference<FileSystemResource> cssBundle = new AtomicReference<>();

        private final ReadWriteLock jsLock = new ReentrantReadWriteLock();
        private final ReadWriteLock cssLock = new ReentrantReadWriteLock();

        private Path tempDir;

        public Mono<FileSystemResource> getJsBundle(String version,
            Supplier<Flux<DataBuffer>> jsSupplier) {
            var fileName = tempFileName(version, ".js");
            return Mono.defer(() -> {
                jsLock.readLock().lock();
                try {
                    var jsBundleResource = jsBundle.get();
                    if (getResourceIfNotChange(fileName, jsBundleResource) != null) {
                        return Mono.just(jsBundleResource);
                    }
                } finally {
                    jsLock.readLock().unlock();
                }

                jsLock.writeLock().lock();
                try {
                    var oldJsBundle = jsBundle.get();
                    return writeBundle(fileName, jsSupplier)
                        .doOnNext(newRes -> jsBundle.compareAndSet(oldJsBundle, newRes));
                } finally {
                    jsLock.writeLock().unlock();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        }

        public Mono<FileSystemResource> getCssBundle(String version,
            Supplier<Flux<DataBuffer>> cssSupplier) {
            var fileName = tempFileName(version, ".css");
            return Mono.defer(() -> {
                try {
                    cssLock.readLock().lock();
                    var cssBundleResource = cssBundle.get();
                    if (getResourceIfNotChange(fileName, cssBundleResource) != null) {
                        return Mono.just(cssBundleResource);
                    }
                } finally {
                    cssLock.readLock().unlock();
                }

                cssLock.writeLock().lock();
                try {
                    var oldCssBundle = cssBundle.get();
                    return writeBundle(fileName, cssSupplier)
                        .doOnNext(newRes -> cssBundle.compareAndSet(oldCssBundle, newRes));
                } finally {
                    cssLock.writeLock().unlock();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Nullable
        private Resource getResourceIfNotChange(String fileName, Resource resource) {
            if (resource != null && resource.exists() && fileName.equals(resource.getFilename())) {
                return resource;
            }
            return null;
        }

        private Mono<FileSystemResource> writeBundle(String fileName,
            Supplier<Flux<DataBuffer>> dataSupplier) {
            return Mono.defer(
                () -> {
                    var filePath = createTempFileToStore(fileName);
                    return DataBufferUtils.write(dataSupplier.get(), filePath)
                        .then(Mono.fromSupplier(() -> new FileSystemResource(filePath)));
                });
        }

        private Path createTempFileToStore(String fileName) {
            try {
                if (tempDir == null || !Files.exists(tempDir)) {
                    this.tempDir = Files.createTempDirectory("halo-plugin-bundle");
                }
                var path = tempDir.resolve(fileName);
                Files.deleteIfExists(path);
                return Files.createFile(path);
            } catch (IOException e) {
                throw new ServerWebInputException("Failed to create temp file.", null, e);
            }
        }

        private String tempFileName(String v, String suffix) {
            Assert.notNull(v, "Version must not be null");
            Assert.notNull(suffix, "Suffix must not be null");
            return v + suffix;
        }

        @Override
        public void destroy() throws Exception {
            if (tempDir != null && Files.exists(tempDir)) {
                FileSystemUtils.deleteRecursively(tempDir);
            }
            this.jsBundle.set(null);
            this.cssBundle.set(null);
        }
    }
}
