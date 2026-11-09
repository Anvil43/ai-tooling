package com.anvil43.youtrackmcp;

import com.anvil43.youtrackmcp.exception.UnexpectedResponseException;
import com.anvil43.youtrackmcp.model.AddedComment;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueComment;
import com.anvil43.youtrackmcp.model.IssueLink;
import com.anvil43.youtrackmcp.model.IssueSummary;
import com.anvil43.youtrackmcp.model.Project;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
import com.anvil43.youtrackmcp.model.UpdatedText;
import com.anvil43.youtrackmcp.model.User;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import javax.swing.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

final class YouTrackResponseMapper {

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

    private static final String RESPONSE_BODY = "response_body";
    private static final String SHORTNAME = "shortName";
    private static final String ARCHIVED = "archived";
    private static final String NAME = "name";
    private static final String SUMMARY = "summary";
    private static final String ID_READABLE = "idReadable";
    private static final String CUSTOM_FIELDS = "customFields";
    private static final String VALUE = "value";
    private static final String TYPE = "$type";
    private static final String FULL_NAME = "fullName";
    private static final String PRESENTATION = "presentation";
    private static final String TEXT = "text";
    private static final String LOGIN = "login";
    private static final String ERROR_CHILDREN = "error_children";
    private static final String ERROR_DESCRIPTION = "error_description";
    private static final String ERROR = "error";
    private static final String COMMENTS = "comments";
    private static final String RESOLVED = "resolved";
    private static final String UPDATED = "updated";
    private static final String CREATED = "created";
    private static final String DESCRIPTION = "description";
    private static final String AUTHOR = "author";
    private static final String ISSUE = "issue";
    private static final String ID = "id";
    private static final String ISSUES = "issues";
    private static final String COMMANDS = "commands";
    private static final String LINKS = "links";
    private static final String DIRECTION = "direction";
    private static final String INWARD = "INWARD";
    private static final String OUTWARD = "OUTWARD";
    private static final String BOTH = "BOTH";
    private static final String SOURCE_TO_TARGET = "sourceToTarget";
    private static final String TARGET_TO_SOURCE = "targetToSource";
    private static final String LINK_TYPE = "linkType";

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private YouTrackResponseMapper() {}

    private static <T> T read(String responseBody, Function<JsonNode, T> mapper) {
        try {
            return mapper.apply(JSON_MAPPER.readTree(responseBody));
        }
        catch (JacksonException e) {
            throw new UnexpectedResponseException("Response body did not match the expected shape.", e);
        }
    }

    static List<Project> projects(String responseBody) {
        return read(responseBody, rootNode -> {
            List<Project> projects = new ArrayList<>();
            for (JsonNode projectNode : requireArray(rootNode, RESPONSE_BODY)) {
                if (!projectNode.path(ARCHIVED).booleanValue()) {
                    projects.add(new Project(
                        projectNode.path(SHORTNAME).stringValue(),
                        projectNode.path(NAME).stringValue()
                    ));
                }
            }
            return projects;
        });
    }

    static List<IssueSummary> issueSummaries(String responseBody) {
        return read(responseBody, rootNode -> {
            List<IssueSummary> issueSummaries = new ArrayList<>();
            for (JsonNode issueNode : requireArray(rootNode, RESPONSE_BODY)) {
                issueSummaries.add(new IssueSummary(
                    issueNode.path(ID_READABLE).stringValue(),
                    issueNode.path(SUMMARY).stringValue(),
                    extractCustomFields(issueNode.path(CUSTOM_FIELDS))
                ));
            }
            return issueSummaries;
        });
    }

    static Issue issue(String responseBody) {
        return read(responseBody, rootNode -> new Issue(
                rootNode.path(ID_READABLE).stringValue(),
                rootNode.path(SUMMARY).stringValue(),
                rootNode.path(DESCRIPTION).stringValue(),
                requiredInstant(rootNode, CREATED),
                requiredInstant(rootNode, UPDATED),
                nullableInstant(rootNode, RESOLVED),
                extractCustomFields(rootNode.path(CUSTOM_FIELDS)),
                extractIssueComments(rootNode.path(COMMENTS)),
                extractIssueLinks(rootNode.path(LINKS))
        ));
    }

    static CreatedIssue createdIssue(String responseBody, String createdIssueUrl) {
        return read(responseBody, rootNode -> {
            String issueId = rootNode.path(ID_READABLE).stringValue();
            return new CreatedIssue(
                    issueId,
                    rootNode.path(SUMMARY).stringValue(),
                    createdIssueUrl + issueId
            );
        });
    }

    static AddedComment addedComment(String responseBody, String addedCommentUrl) {
        return read(responseBody, rootNode -> {
            rootNode.path(ID).stringValue();
            JsonNode issueNode = rootNode.path(ISSUE);
            if (issueNode.isMissingNode()) {
                throw new UnexpectedResponseException("Missing required property \"" + ISSUE + "\".");
            }
            String issueId = issueNode.path(ID_READABLE).stringValue();
            return new AddedComment(
                    issueId,
                    addedCommentUrl + issueId
            );
        });
    }

    static UpdatedIssue updatedIssue(String responseBody, String updatedIssueUrl) {
        return read(responseBody, rootNode -> {
            String issueId = rootNode.path(ISSUES).path(0).path(ID_READABLE).stringValue();
            ArrayNode commandsArray = rootNode.path(COMMANDS).asArray();
            List<String> commandsResult = extractCommandsFromCommandsArray(commandsArray);
            return new UpdatedIssue(issueId, commandsResult, updatedIssueUrl + issueId);
        });
    }

    static UpdatedText updatedText(String responseBody, String updatedIssueUrl) {
        return read(responseBody, rootNode -> {
            String issueId = rootNode.path(ID_READABLE).stringValue();
            if (null == issueId) {
                throw new UnexpectedResponseException("Required property \"" + ID_READABLE + "\" has a null value.");
            }
            return new UpdatedText(issueId, updatedIssueUrl + issueId);
        });
    }

    static String errorMessage(String responseBody) {
        return read(responseBody, rootNode -> rootNode.path(ERROR_DESCRIPTION).stringValue());
    }

    static String commandErrorMessage(String responseBody) {
        return read(responseBody, rootNode -> cleanupDescription(rootNode.path(ERROR_DESCRIPTION).stringValue()));
    }

    static String queryErrorMessage(String responseBody) {
        return read(responseBody, rootNode -> {
            String errorDescription = rootNode.path(ERROR_DESCRIPTION).stringValue();
            if (rootNode.path(ERROR_CHILDREN).isArray() && !rootNode.path(ERROR_CHILDREN).asArray().isEmpty()) {
               List<String> errors = new ArrayList<>();
               for (JsonNode errorChildNode : rootNode.get(ERROR_CHILDREN)) {
                   if (!errorChildNode.isObject()) {
                       throw new UnexpectedResponseException("Error child node was not an object.");
                   }
                   errors.add(errorChildNode.path(ERROR).stringValue());
               }
               String errorMessage = String.join("; ", errors);
               return errorDescription + " (" + errorMessage + ")";
            }
            else {
               return errorDescription;
            }
        });
    }

    private static JsonNode requireArray(JsonNode node, String fieldName) {
        if (!node.isArray()) {
            throw new UnexpectedResponseException("Expected " + fieldName + " to be an array. Got " + node.getNodeType() + " instead.");
        }
        return node;
    }

    private static Instant requiredInstant(JsonNode node, String fieldName) {
        return Instant.ofEpochMilli(node.path(fieldName).longValue());
    }

    private static Instant nullableInstant(JsonNode node, String fieldName) {
        if (node.path(fieldName).isNull()) {
            return null;
        }
        return Instant.ofEpochMilli(node.path(fieldName).longValue());
    }

    private static List<CustomField> extractCustomFields(JsonNode customFieldsNode) {
        List<CustomField> customFields = new ArrayList<>();
        for (JsonNode customFieldNode : requireArray(customFieldsNode, CUSTOM_FIELDS)) {
            customFields.add(extractCustomField(customFieldNode));
        }
        return customFields;
    }

    private static CustomField extractCustomField(JsonNode customFieldNode) {
        String name = customFieldNode.path(NAME).stringValue();
        JsonNode valueNode = customFieldNode.path(VALUE);
        if (valueNode.isMissingNode()) {
            throw new UnexpectedResponseException("Missing required property \"" + VALUE + "\".");
        }
        return new CustomField(name, renderValue(valueNode));
    }

    private static String renderValue(JsonNode valueNode) {
        if (valueNode.isNull() || (valueNode.isArray() && valueNode.isEmpty())) {
            return null;
        }
        if (valueNode.isArray()) {
            List<String> parts = new ArrayList<>();
            valueNode.asArray().forEach(element -> parts.add(renderValue(element)));
            return String.join(", ", parts);
        }
        if (valueNode.isObject()) {
            String type = valueNode.path(TYPE).stringValue();
            return renderObject(valueNode, type);
        }
        return valueNode.asString();
    }

    private static String renderObject(JsonNode valueNode, String type) {
        for (String fieldName : List.of(FULL_NAME, NAME, PRESENTATION, TEXT, LOGIN)) {
            String fieldValue = valueNode.path(fieldName).stringValue(null);
            if (null != fieldValue) {
                return fieldValue;
            }
        }
        return "(unrecognised " +  type + ")";
    }

    private static List<IssueComment> extractIssueComments(JsonNode issueCommentsNode) {
        List<IssueComment> issueComments = new ArrayList<>();
        for (JsonNode issueComment : requireArray(issueCommentsNode, COMMENTS)) {
            if (!issueComment.isObject()) {
                throw new UnexpectedResponseException("Issue comment was not an object.");
            }
            issueComments.add(new IssueComment(
                extractTextFromTextNode(issueComment.path(TEXT)),
                requiredInstant(issueComment, CREATED),
                extractAuthorFromJsonNode(issueComment.path(AUTHOR))
            ));
        }
        return issueComments;
    }

    private static String extractTextFromTextNode(JsonNode textNode) {
        if (textNode.isMissingNode()) {
            throw new UnexpectedResponseException("Missing required property \"" + TEXT + "\".");
        }
        return textNode.stringValue();
    }

    private static User extractAuthorFromJsonNode(JsonNode authorNode) {
        if (authorNode.isMissingNode()) {
            throw new UnexpectedResponseException("Missing required property \"" + AUTHOR + "\".");
        }
        else if (!authorNode.isObject()) {
            throw new UnexpectedResponseException("Author was not an object.");
        }
        else {
            return new User(
                authorNode.path(LOGIN).stringValue(),
                authorNode.path(FULL_NAME).stringValue()
            );
        }
    }

    private static List<String> extractCommandsFromCommandsArray(ArrayNode commandsArray) {
        List<String> extractedCommands = new ArrayList<>();
        for (JsonNode commandNode : commandsArray) {
            if (commandNode.path(ERROR).asBoolean(false)) {
                throw new UnexpectedResponseException("YouTrack flagged a command as an error in a successful response.");
            }
            String rawDescription = commandNode.path(DESCRIPTION).stringValue();
            extractedCommands.add(cleanupDescription(rawDescription));
        }
        return extractedCommands;
    }

    private static String cleanupDescription(String description) {
        description = TAG_PATTERN.matcher(description).replaceAll("");
        description = description.replace("&lt;", "<");
        description = description.replace("&gt;", ">");
        description = description.replace("&quot;", "\"");
        description = description.replace("&#39;", "'");
        description = description.replace("&amp;", "&");
        return description;
    }

    private static List<IssueLink> extractIssueLinks(JsonNode linksNode) {
        List<IssueLink> issueLinks = new ArrayList<>();
        for (JsonNode linkNode : requireArray(linksNode, LINKS)) {
            if (!linkNode.isObject()) {
                throw new UnexpectedResponseException("Issue link was not an object.");
            }
            issueLinks.addAll(handleLinkNode(linkNode));
        }
        return issueLinks;
    }

    private static List<IssueLink> handleLinkNode(JsonNode linkNode) {
        List<IssueLink> issueLinks = new ArrayList<>();
        String relation = extractRelationValueFromJsonNode(linkNode);
        JsonNode linkedIssues = linkNode.path(ISSUES);
        for (JsonNode issueLink : requireArray(linkedIssues, ISSUES)) {
            String idReadable = issueLink.path(ID_READABLE).stringValue();
            if (null == idReadable) {
                throw new UnexpectedResponseException("Id readable of a linked issue may not be null.");
            }
            issueLinks.add(new IssueLink(
                relation,
                idReadable,
                issueLink.path(SUMMARY).stringValue()
                ));
        }
        return issueLinks;
    }

    private static String extractRelationValueFromJsonNode(JsonNode linkNode) {
        JsonNode directionNode = linkNode.path(DIRECTION);
        if (directionNode.isMissingNode()) {
            throw new UnexpectedResponseException("Missing required property \"direction\".");
        }
        String direction = directionNode.asString();
        String relation;
        JsonNode linkTypeNode = linkNode.path(LINK_TYPE);
        if (direction.equals(OUTWARD) || direction.equals(BOTH)) {
            relation = linkTypeNode.path(SOURCE_TO_TARGET).stringValue();
        }
        else if (direction.equals(INWARD)) {
            relation = linkTypeNode.path(TARGET_TO_SOURCE).stringValue();
        }
        else {
            throw new UnexpectedResponseException("Unrecognized direction: " + direction);
        }
        if ((relation == null) || (relation.isBlank())) {
            relation = linkTypeNode.path(NAME).stringValue();
        }
        return relation;
    }

}
