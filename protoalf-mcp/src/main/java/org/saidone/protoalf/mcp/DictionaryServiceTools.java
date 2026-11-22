package org.saidone.protoalf.mcp;

import lombok.RequiredArgsConstructor;
import org.saidone.protoalf.client.DictionaryServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;


import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by DictionaryServiceClient. */
@RequiredArgsConstructor
public class DictionaryServiceTools {

    private final DictionaryServiceClient dictionary;

    @Tool(name = "alfresco_list_models", description = """
        Lists the content models of the Alfresco data dictionary with their types and aspects \
        (name, title, parent, mandatory aspects). Use it to discover which types exist (e.g. for \
        TYPE:"..." in alfresco_search) and, with includeProperties, which metadata properties a type \
        or aspect accepts and their data types (for the properties of the create/update tools). \
        Without 'model' it lists every model, without properties. With 'model' (a namespace prefix such \
        as cm, or a model name such as cm:contentmodel) it lists that model only. Properties are those \
        a type/aspect defines itself: inherited ones are on its parent. readOnly properties cannot be set.""")
    public String listModels(@ToolParam(description = "Namespace prefix (e.g. cm) or model name (e.g. cm:contentmodel); omit for every model", required = false) String model, @ToolParam(description = "Also list the properties of each type and aspect; requires model", required = false) Boolean includeProperties) {
        return run(() -> dictionary.getAllModels(text(model), Boolean.TRUE.equals(includeProperties)));
    }

}
