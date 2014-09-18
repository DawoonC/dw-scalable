package com.google.devrel.training.conference.form;

import java.util.Date;

/**
 * A simple Java object (POJO) representing a Session form sent from the client.
 */
public class SessionForm {
    /**
     * The name of the session.
     */
    private String name;

    /**
     * The highlights of the session.
     */
    private String highlights;

    /**
     * The speaker of the session.
     */
    private String speaker;

    /**
     * The type of the session.
     */
    private String typeOfSession;

    /**
     * The start time of the session.
     */
    private int startTime;

    /**
     * The date of the session.
     */
    private Date date;

    /**
     * The duration of the session.
     */
    private int duration;

    private SessionForm() {}

    /**
     * Public constructor is solely for Unit Test.
     * @param name
     * @param highlights
     * @param speaker
     * @param typeOfSession
     * @param startTime
     * @param date
     * @param duration
     */
    public SessionForm(String name, String highlights, String speaker, String typeOfSession,
                          int startTime, Date date, int duration) {
        this.name = name;
        this.highlights = highlights;
        this.speaker = speaker;
        this.typeOfSession = typeOfSession;
        this.startTime = startTime;
        this.date = date == null ? null : new Date(date.getTime());
        this.duration = duration;
    }

    public String getName() {
        return name;
    }

    public String getHighlights() {
        return highlights;
    }

    public String getSpeaker() {
        return speaker;
    }

    public String getTypeOfSession() {
        return typeOfSession;
    }

    public int getStartTime() {
        return startTime;
    }

    public Date getDate() {
        return date;
    }

    public int getDuration() {
        return duration;
    }
}
