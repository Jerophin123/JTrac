package com.jtac.search.internal;

import com.jtac.organization.OrgContext;
import com.jtac.search.SavedViews;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link SavedViews} on top of {@link SavedViewService}. */
@Component
class SavedViewsAdapter implements SavedViews {

    private final SavedViewService views;

    SavedViewsAdapter(SavedViewService views) {
        this.views = views;
    }

    @Override
    public List<View> list(OrgContext org, String projectKey) {
        return views.list(org, projectKey).stream().map(SavedViewsAdapter::toPublic).toList();
    }

    @Override
    public View get(OrgContext org, UUID id) {
        return toPublic(views.get(org, id));
    }

    private static View toPublic(SavedViewService.SavedView v) {
        return new View(v.id(), v.name(), v.kind(), v.projectKey(), v.query(), v.shared(), v.ownerName());
    }
}
