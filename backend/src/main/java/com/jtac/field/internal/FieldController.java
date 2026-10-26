package com.jtac.field.internal;

import com.jtac.field.FieldDefinition;
import com.jtac.field.FieldType;
import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FieldController {

    private static final String FIELDS = ApiPaths.V1 + "/orgs/{org}/projects/{key}/fields";

    private final OrganizationAccess organizations;
    private final ProjectAccess projects;
    private final FieldService fields;

    FieldController(OrganizationAccess organizations, ProjectAccess projects, FieldService fields) {
        this.organizations = organizations;
        this.projects = projects;
        this.fields = fields;
    }

    private ProjectContext ctx(CurrentUser user, String org, String key) {
        return projects.require(organizations.require(user, org), key);
    }

    @GetMapping(FIELDS)
    List<FieldDefinition> list(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return fields.fields(ctx(user, org, key).projectId());
    }

    @PostMapping(FIELDS)
    ResponseEntity<FieldDefinition> create(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody CreateField body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fields.create(ctx(user, org, key), body.key(), body.name(),
                body.type(), body.options(), body.description()));
    }

    @PatchMapping(FIELDS + "/{fieldId}")
    FieldDefinition update(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID fieldId,
            @Valid @RequestBody UpdateField body) {
        return fields.update(ctx(user, org, key), fieldId, body.name(), body.options(), body.description(), body.position(),
                body.archived(), body.version());
    }

    record CreateField(@Size(max = 40) String key, @NotBlank @Size(max = 60) String name, FieldType type,
            List<FieldService.OptionInput> options, @Size(max = 500) String description) {
    }

    record UpdateField(@NotBlank @Size(max = 60) String name, List<FieldService.OptionInput> options,
            @Size(max = 500) String description, Integer position, boolean archived, long version) {
    }
}
