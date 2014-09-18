package com.google.devrel.training.conference.domain;

import static com.google.devrel.training.conference.service.OfyService.ofy;

import com.googlecode.objectify.condition.IfNotDefault;
import com.google.api.server.spi.config.AnnotationBoolean;
import com.google.api.server.spi.config.ApiResourceProperty;
import com.google.common.base.Preconditions;
import com.google.devrel.training.conference.form.SessionForm;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

import java.util.Date;

import javax.inject.Named;

/**
 * Session class stores session information.
 */
@Entity
@Cache
public class Session {

    private static final String DEFAULT_SPEAKER = "Undefined";

    private static final String DEFAULT_TYPE_OF_SESSION = "Undefined";

    /**
     * The id for the datastore key.
     *
     * We use automatic id assignment for entities of Conference class.
     */
    @Id
    private long id;

    /**
     * The name of the session.
     */
    @Index
    private String name;

    /**
     * The highlights of the session.
     */
    private String highlights;

    /**
     * Holds Conference key as the parent.
     */
    @Parent
    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    private Key<Conference> conferenceKey;

    /**
     * The conferenceId of the conference.
     */
    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    private Long conferenceId;

    /**
     * Type of this session.
     */
    @Index(IfNotDefault.class)
    private String typeOfSession;

    /**
     * The name of the speaker for this session.
     */
    @Index(IfNotDefault.class) 
    private String speaker;

    /**
     * The starting time of this session.
     */
    @Index(IfNotDefault.class)
    private int startTime;

    /**
     * The date of this session.
     */
    private Date date;

    /**
     * Duration of the session in minutes.
     */
    @Index(IfNotDefault.class)
    private int duration;

    /**
     * Just making the default constructor private.
     */
    private Session() {}

    public Session(final long id, @Named("websafeConferenceKey") final String websafeConferenceKey,
                      final SessionForm sessionForm) {
        Preconditions.checkNotNull(sessionForm.getName(), "The name is required");
        this.id = id;
        this.conferenceKey = Key.create(websafeConferenceKey);
        this.conferenceId = conferenceKey.getId();
        updateWithSessionForm(sessionForm);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHighlights() {
        return highlights;
    }

    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    public Key<Conference> getConferenceKey() {
        return conferenceKey;
    }

    // Get a String version of the key
    public String getWebsafeKey() {
        return Key.create(conferenceKey, Session.class, id).getString();
    }

    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    public Long getConferenceId() {
        return conferenceId;
    }

    /**
     * Returns conference name.
     *
     * @return conference name. If there is no conference, return conferenceId.
     */
    public String getConferenceName() {
        Conference conference = ofy().load().key(getConferenceKey()).now();
        return conference.getName();
    }

    /**
     * Returns a speaker's name.
     * @return a speaker's name.
     */
    public String getSpeaker() {
        return speaker;
    }

    /**
     * Returns type of the session.
     * @return type of the session.
     */
    public String getTypeOfSession() {
        return typeOfSession;
    }

    /**
     * Returns start time of the session.
     * @return start time of the session.
     */
    public int getStartTime() {
        return startTime;
    }

    /**
     * Returns a defensive copy of Date if not null.
     * @return a defensive copy of Date if not null.
     */
    public Date getDate() {
        return date == null ? null : new Date(date.getTime());
    }

    /**
     * Returns duration of this session.
     * @return duration of this session.
     */
    public int getDuration() {
        return duration;
    }

    /**
     * Updates the Session with SessionForm.
     * This method is used upon object creation as well as updating existing sessions.
     *
     * @param sessionForm contains form data sent from the client.
     */
    public void updateWithSessionForm(SessionForm sessionForm) {
        this.name = sessionForm.getName();
        this.highlights = sessionForm.getHighlights();
        this.speaker = sessionForm.getSpeaker() == null ? DEFAULT_SPEAKER : sessionForm.getSpeaker();
        this.typeOfSession = sessionForm.getTypeOfSession() == null ? DEFAULT_TYPE_OF_SESSION : sessionForm.getTypeOfSession();
        this.duration = sessionForm.getDuration();
        this.startTime = sessionForm.getStartTime();
        if (this.startTime > 24 || this.startTime < 0) {
            this.startTime = 0;
        }
        Date date = sessionForm.getDate();
        this.date = date == null ? null : new Date(date.getTime());

        if (this.date != null) {
            this.date.setHours(this.startTime);            
        }
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("Id: " + id + "\n")
                .append("Name: ").append(name).append("\n");
        if (typeOfSession != null) {
            stringBuilder.append("TypeOfSession: ").append(typeOfSession).append("\n");
        }
        if (speaker != null) {
            stringBuilder.append("Speaker: ").append(speaker).append("\n");
        }
        if (date != null) {
            stringBuilder.append("Date: ").append(date.toString()).append("\n");
        }
        if (startTime != 0) {
            stringBuilder.append("StartTime: ").append(String.valueOf(startTime)).append("\n");
        }
        if (duration != 0) {
            stringBuilder.append("Duration: ").append(String.valueOf(duration)).append(" minutes").append("\n");
        }
        return stringBuilder.toString();
    }

}
