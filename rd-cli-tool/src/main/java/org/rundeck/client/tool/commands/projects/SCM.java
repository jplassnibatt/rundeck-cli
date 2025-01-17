/*
 * Copyright 2017 Rundeck, Inc. (http://rundeck.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rundeck.client.tool.commands.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import okhttp3.RequestBody;
import org.rundeck.client.api.RequestFailed;
import org.rundeck.client.api.RundeckApi;
import org.rundeck.client.api.model.*;
import org.rundeck.client.tool.CommandOutput;
import org.rundeck.client.tool.InputError;
import org.rundeck.client.tool.extension.BaseCommand;
import org.rundeck.client.tool.options.OptionUtil;
import org.rundeck.client.tool.options.ProjectNameOptions;
import org.rundeck.client.tool.options.ProjectRequiredNameOptions;
import org.rundeck.client.tool.options.VerboseOption;
import org.rundeck.client.tool.util.Colorz;
import org.rundeck.client.util.Client;
import org.rundeck.client.util.ServiceClient;
import picocli.CommandLine;
import retrofit2.Response;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * scm subcommands
 */

@CommandLine.Command(description = "Manage Project SCM", name = "scm")
public class SCM extends BaseCommand {
    static final List<String> INTEGRATIONS = new ArrayList<>(Arrays.asList("import", "export"));

    static class IntegrationCandidates extends ArrayList<String> {
        IntegrationCandidates() {
            super(INTEGRATIONS);
        }
    }

    @Getter
    @Setter
    static class BaseOpts extends ProjectNameOptions {
        @CommandLine.Option(names = {"--integration", "-i"},
                completionCandidates = IntegrationCandidates.class,
                description = "Integration type: ${COMPLETION-CANDIDATES}",
                required = true
        )
        private String integration;
    }

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec; // injected by picocli

    String validate(BaseOpts opts) throws InputError {
        if (!INTEGRATIONS.contains(opts.getIntegration())) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--integration/-i must be one of: " + INTEGRATIONS);
        }
        if (null != opts.getProject()) {
            ProjectRequiredNameOptions.validateProjectName(opts.getProject(), spec);
        }
        return getRdTool().projectOrEnv(opts);
    }

    @CommandLine.Command(description = "Get SCM Config for a Project")
    public void config(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Option(names = {"--file", "-f"}, description = "If specified, write config to a file (json format)")
            File file
    ) throws IOException, InputError {
        String project = validate(opts);
        ScmConfig scmConfig1 = apiCall(api -> api.getScmConfig(project, opts.getIntegration()));

        HashMap<String, Object> basic = new HashMap<>();
        basic.put("Project", scmConfig1.project);
        basic.put("SCM Plugin type", scmConfig1.type);
        basic.put("SCM Plugin integration", scmConfig1.integration);
        getRdOutput().info(basic);

        HashMap<String, Object> map = new HashMap<>();
        map.put("config", scmConfig1.config);
        if (file != null) {
            //write to file
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(file, map);
            getRdOutput().info("Wrote config to file: " + file);
        } else {
            getRdOutput().output(map);
        }
    }

    @Getter @Setter
    static class TypeOptions {
        @CommandLine.Option(names = {"--type", "-t"}, description = "Plugin type", required = true)
        private String type;
    }

    @Getter @Setter
    static class FileOptions {
        @CommandLine.Option(names = {"--file", "-f"}, description = "Config file (json format)", required = true)
        private File file;
    }

    @CommandLine.Command(description = "Setup SCM Config for a Project")
    public boolean setup(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin TypeOptions typeOptions,
            @CommandLine.Mixin FileOptions fileOptions
    ) throws IOException, InputError {
        String project = validate(opts);
        /*
         * body containing the file
         */
        RequestBody requestBody = RequestBody.create(
                fileOptions.getFile(),
                Client.MEDIA_TYPE_JSON
        );

        //get response to handle 400 validation error
        ServiceClient.WithErrorResponse<ScmActionResult> response = getRdTool().apiWithErrorResponse(api -> api
                .setupScmConfig(
                        project,
                        opts.getIntegration(),
                        typeOptions.getType(),
                        requestBody
                ));

        //check for 400 error with validation information
        if (hasValidationError(
                getRdOutput(),
                getRdTool().getClient(),
                response,
                "Setup config Validation for file: " + fileOptions.getFile().getAbsolutePath(),
                getRdTool().getAppConfig().isAnsiEnabled()
        )) {
            return false;
        }

        //otherwise check other error codes and fail if necessary
        ScmActionResult result = getRdTool().getClient().checkError(response);


        return outputScmActionResult(getRdOutput(), result, "Setup");
    }

    private boolean outputScmActionResult(final CommandOutput output, final ScmActionResult result, final String name) {
        if (result.success) {
            getRdOutput().info(name + " was successful.");
        } else {
            getRdOutput().warning(name + " was not successful.");
        }
        if (result.message != null) {
            getRdOutput().info("Result: " + result.message);
        }
        if (result.nextAction != null) {
            getRdOutput().output(CommandLine.Help.Ansi.AUTO.string(
                    "Next Action: @|green " + result.nextAction + "|@"
            ));
        }

        return result.success;
    }


    @CommandLine.Command(description = "Get SCM Status for a Project")
    public boolean status(@CommandLine.Mixin BaseOpts opts) throws IOException, InputError {
        String project = validate(opts);
        ScmProjectStatusResult result = apiCall(api -> api.getScmProjectStatus(
                project,
                opts.getIntegration()
        ));


        getRdOutput().output(result.toMap());
        return result.synchState == ScmSynchState.CLEAN;
    }


    @CommandLine.Command(description = "Enable plugin ")
    public void enable(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin TypeOptions options
    ) throws IOException, InputError {
        String project = validate(opts);
        apiCall(api -> api.enableScmPlugin(
                project,
                opts.getIntegration(),
                options.getType()
        ));
    }

    @CommandLine.Command(description = "Disable plugin ")
    public void disable(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin TypeOptions options
    ) throws IOException, InputError {
        //otherwise check other error codes and fail if necessary
        String project = validate(opts);

        apiCall(api -> api.disableScmPlugin(
                project,
                opts.getIntegration(),
                options.getType()
        ));

    }


    @CommandLine.Command(description = "Get SCM Setup inputs")
    public void setupinputs(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin TypeOptions options,
            @CommandLine.Mixin VerboseOption verboseOption
    ) throws IOException, InputError {

        String project = validate(opts);
        //otherwise check other error codes and fail if necessary
        ScmSetupInputsResult result = apiCall(api -> api.getScmSetupInputs(
                project,
                opts.getIntegration(),
                options.getType()
        ));

        if (verboseOption.isVerbose()) {
            getRdOutput().output(result);
            return;
        }

        getRdOutput().output(result.fields.stream().map(ScmInputField::asMap).collect(Collectors.toList()));
    }

    @Getter @Setter
    public static class ActionInputsOptions {
        @CommandLine.Option(names = {"--action", "-a"}, description = "Action ID", required = true)
        private String action;
    }

    @CommandLine.Command(description = "Get SCM action inputs")
    public void inputs(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin ActionInputsOptions options,
            @CommandLine.Mixin VerboseOption verboseOption
    ) throws IOException, InputError {
        String project = validate(opts);

        ScmActionInputsResult result = apiCall(api -> api.getScmActionInputs(
                project,
                opts.getIntegration(),
                options.getAction()
        ));

        if (verboseOption.isVerbose()) {
            getRdOutput().output(result);
            return;
        }
        getRdOutput().output(result.title + ": " + result.description);
        getRdOutput().output("Fields:");
        getRdOutput().output(result.fields.stream().map(ScmInputField::asMap).collect(Collectors.toList()));
        getRdOutput().output("Items:");
        if ("export".equals(opts.getIntegration())) {
            getRdOutput().output(result.exportItems.stream().map(ScmExportItem::asMap).collect(Collectors.toList()));
        } else {
            getRdOutput().output(result.importItems.stream().map(ScmImportItem::asMap).collect(Collectors.toList()));
        }
    }

    @Getter @Setter
    public static class ActionPerformOptions extends ActionInputsOptions {
        @CommandLine.Option(names = {"--field", "-f"}, arity = "1..*", description = "Field input values, space separated key=value list")
        private List<String> fields;

        @CommandLine.Option(names = {"--item", "-I"}, arity = "1..*", description = "Items to include, space separated list")
        private List<String> item;

        @CommandLine.Option(names = {"-A", "--allitems"},
                description = "Include all items from the result of calling Inputs in export or import action")
        boolean allItems;

        @CommandLine.Option(names = {"-M", "--allmodified"},
                description = "Include all modified (not deleted) items from the result of calling Inputs in Export " +
                        "action (export only)")
        boolean allModifiedItems;

        @CommandLine.Option(names = {"-D", "--alldeleted"},
                description = "Include all deleted items from the result of calling Inputs in Export " +
                        "action (export only)")
        boolean allDeletedItems;

        @CommandLine.Option(names = {"-T", "--alltracked"},
                description = "Include all tracked (not new) items from the result of calling Inputs in Import action" +
                        " (import only)")
        boolean allTrackedItems;

        @CommandLine.Option(names = {"-U", "--alluntracked"},
                description = "Include all untracked (new) items from the result of calling Inputs in Import action " +
                        "(import only)")
        boolean allUntrackedItems;

        @CommandLine.Option(names = {"--job", "-j"}, arity = "1..*", description = "Job IDs to include, space separated list")
        List<String> job;


        @CommandLine.Option(names = {"--delete", "-d"},
                arity = "1..*",
                description = "Job IDs or Item Ids to delete, space separated list")
        List<String> delete;

    }

    @CommandLine.Command(description = "Perform SCM action")
    public boolean perform(
            @CommandLine.Mixin BaseOpts opts,
            @CommandLine.Mixin ActionPerformOptions options
    ) throws IOException, InputError {

        ScmActionPerform perform = performFromOptions(options);
        String project = validate(opts);
        boolean export = "export".equals(opts.getIntegration());
        if (options.isAllItems() ||
                export && (options.isAllDeletedItems() || options.isAllModifiedItems()) ||
                !export && (options.isAllTrackedItems() || options.isAllUntrackedItems())) {
            //call the Inputs endpoint to list the items for the action
            ScmActionInputsResult inputs = apiCall(api -> api.getScmActionInputs(
                    project,
                    opts.getIntegration(),
                    options.getAction()
            ));
            if (export) {
                List<ScmExportItem> exportItems = inputs.exportItems;
                if (options.isAllItems() || options.isAllModifiedItems()) {
                    perform.setItems(exportItems.stream()
                                                .filter(a -> !a.getDeleted())
                                                .map(a -> a.itemId)
                                                .collect(Collectors.toList()));
                }
                if (options.isAllItems() || options.isAllDeletedItems()) {
                    perform.setDeleted(exportItems.stream()
                                                  .filter(ScmExportItem::getDeleted)
                                                  .map(a -> a.itemId)
                                                  .collect(Collectors.toList()));
                }
            } else {
                List<ScmImportItem> importItems = inputs.importItems;
                perform.setItems(
                        importItems.stream()
                                   .filter(a -> !a.deleted && (
                                                   options.isAllItems() ||
                                                   options.isAllTrackedItems() && a.tracked ||
                                                   options.isAllUntrackedItems() && !a.tracked
                                           )
                                   )
                                   .map(a -> a.itemId)
                                   .collect(Collectors.toList())
                );
                perform.setDeletedJobs(
                        importItems.stream()
                                   .filter(a -> a.deleted && a.job != null && (
                                                   options.isAllItems() ||
                                                   options.isAllTrackedItems() && a.tracked ||
                                                   options.isAllUntrackedItems() && !a.tracked
                                           )
                                   )
                                   .map(a -> a.job.jobId)
                                   .collect(Collectors.toList())
                );
            }
        }
        ServiceClient.WithErrorResponse<ScmActionResult> response = getRdTool().apiWithErrorResponse(api -> api.performScmAction(
                project,
                opts.getIntegration(),
                options.getAction(),
                perform
        ));

        //check for 400 error with validation information
        if (hasValidationError(
                getRdOutput(),
                getRdTool().getClient(),
                response,
                "Action " + options.getAction(),
                getRdTool().getAppConfig().isAnsiEnabled()
        )) {
            return false;
        }

        //otherwise check other error codes and fail if necessary
        ScmActionResult result = getRdTool().getClient().checkError(response);
        return outputScmActionResult(getRdOutput(), result, "Action " + options.getAction());
    }

    private ScmActionPerform performFromOptions(final ActionPerformOptions options) throws InputError {
        ScmActionPerform perform = new ScmActionPerform();
        if (null != options.getFields()) {
            perform.setInput(OptionUtil.parseKeyValueMap(options.getFields(), null, "="));
        } else {
            perform.setInput(new HashMap<>());
        }
        List<String> item = options.getItem();
        perform.setItems(item != null ? item : new ArrayList<>());
        List<String> job = options.getJob();
        perform.setJobs(job != null ? job : new ArrayList<>());
        List<String> delete = options.getDelete();
        perform.setDeleted(delete != null ? delete : new ArrayList<>());
        return perform;
    }

    @CommandLine.Command(description = "List SCM plugins")
    public void plugins(@CommandLine.Mixin BaseOpts opts) throws IOException, InputError {
        String project = validate(opts);
        ScmPluginsResult result = apiCall(api -> api.listScmPlugins(project, opts.getIntegration()));
        getRdOutput().output(result.plugins.stream().map(ScmPlugin::toMap).collect(Collectors.toList()));
    }

    /**
     * Check for validation info from resposne
     *
     * @param name     action name for error messages
     * @param colorize true to colorize output
     */
    private static boolean hasValidationError(
            CommandOutput output,
            final ServiceClient<RundeckApi> serviceClient,
            final ServiceClient.WithErrorResponse<ScmActionResult> errorResponse,
            final String name, final boolean colorize
    )
    {
        Response<ScmActionResult> response = errorResponse.getResponse();
        if (errorResponse.isError400()) {
            try {
                //parse body as ScmActionResult
                ScmActionResult error = serviceClient.readError(
                        errorResponse.getErrorBody(),
                        ScmActionResult.class,
                        Client.MEDIA_TYPE_JSON
                );
                if (null != error) {
                    //
                    output.error(String.format("%s failed", name));
                    if (null != error.message) {
                        output.warning(error.message);
                    }
                    Optional<? extends Map<?, ?>> errorData = Optional.ofNullable(error.toMap());
                    errorData.ifPresent(map -> output.output(
                            colorize ?
                                    Colorz.colorizeMapRecurse(
                                            map,
                                            "yellow"
                                    ) : map
                    ));
                }
            } catch (IOException e) {
                //unable to parse body as expected
                e.printStackTrace();
                throw new RequestFailed(String.format(
                        "%s failed: (error: %d %s)",
                        name,
                        response.code(),
                        response.message()

                ), response.code(), response.message());
            }

        }
        return !response.isSuccessful();
    }


}
