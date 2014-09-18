package com.google.devrel.training.conference.spi;

import static com.google.devrel.training.conference.service.OfyService.ofy;
import static com.google.devrel.training.conference.service.OfyService.factory;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

import javax.inject.Named;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.ApiMethod.HttpMethod;
import com.google.api.server.spi.response.ConflictException;
import com.google.api.server.spi.response.ForbiddenException;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.users.User;
import com.google.common.base.Joiner;
import com.google.devrel.training.conference.Constants;
import com.google.devrel.training.conference.domain.Announcement;
import com.google.devrel.training.conference.domain.Profile;
import com.google.devrel.training.conference.form.ProfileForm;
import com.google.devrel.training.conference.form.ProfileForm.TeeShirtSize;
import com.google.devrel.training.conference.domain.Conference;
import com.google.devrel.training.conference.domain.Session;
import com.google.devrel.training.conference.form.ConferenceForm;
import com.google.devrel.training.conference.form.ConferenceQueryForm;
import com.google.devrel.training.conference.form.SessionForm;
import com.google.devrel.training.conference.form.SessionQueryForm;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.cmd.Query;


/**
 * Defines conference APIs.
 */
@Api(name = "conference", 
     version = "v1", 
     scopes = { Constants.EMAIL_SCOPE }, 
     clientIds = {
        Constants.WEB_CLIENT_ID, 
        Constants.API_EXPLORER_CLIENT_ID }, 
     description = "API for the Conference Central Backend application.")
public class ConferenceApi {

    /*
     * Get the display name from the user's email. For example, if the email is
     * lemoncake@example.com, then the display name becomes "lemoncake."
     */
    private static String extractDefaultDisplayNameFromEmail(String email) {
        return email == null ? null : email.substring(0, email.indexOf("@"));
    }

    /**
     * Creates or updates a Profile object associated with the given user
     * object.
     *
     * @param user
     *            A User object injected by the cloud endpoints.
     * @param profileForm
     *            A ProfileForm object sent from the client form.
     * @return Profile object just created.
     * @throws UnauthorizedException
     *             when the User object is null.
     */

    @ApiMethod(name = "saveProfile", path = "profile", httpMethod = HttpMethod.POST)
    public Profile saveProfile(final User user, ProfileForm profileForm) throws UnauthorizedException {
        if (user == null) {
        	throw new UnauthorizedException("Authorization required");
        }

        TeeShirtSize teeShirtSize = profileForm.getTeeShirtSize();

        String displayName = profileForm.getDisplayName();

        String mainEmail = user.getEmail();
        String userId = user.getUserId();

        Profile profile = getProfile(user); 
        
        if (profile == null) {
        	if (displayName == null) {
        		displayName = extractDefaultDisplayNameFromEmail(user.getEmail());
        	} if (teeShirtSize == null) {
        		teeShirtSize = TeeShirtSize.NOT_SPECIFIED;
        	}
        	profile = new Profile(userId, displayName, mainEmail, teeShirtSize);
        } else {
        	profile.update(displayName, teeShirtSize);
        }
         
        ofy().save().entity(profile).now();

        return profile;
    }

    /**
     * Returns a Profile object associated with the given user object. The cloud
     * endpoints system automatically inject the User object.
     *
     * @param user
     *            A User object injected by the cloud endpoints.
     * @return Profile object.
     * @throws UnauthorizedException
     *             when the User object is null.
     */
    @ApiMethod(name = "getProfile", path = "profile", httpMethod = HttpMethod.GET)
    public Profile getProfile(final User user) throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        String userId = user.getUserId(); 
        Key<Profile> key = Key.create(Profile.class, userId); 
        Profile profile = (Profile) ofy().load().key(key).now(); 

        return profile;
    }
    
    /**
     * Gets the Profile entity for the current user
     * or creates it if it doesn't exist
     * @param user
     * @return user's Profile
     */
    private static Profile getProfileFromUser(User user) {
        Profile profile = ofy().load().key(
                Key.create(Profile.class, user.getUserId())).now();
        if (profile == null) {
            String email = user.getEmail();
            profile = new Profile(user.getUserId(),
                    extractDefaultDisplayNameFromEmail(email), email, TeeShirtSize.NOT_SPECIFIED);
        }
        return profile;
    }

    /**
     * Creates a new Conference object and stores it to the datastore.
     *
     * @param user A user who invokes this method, null when the user is not signed in.
     * @param conferenceForm A ConferenceForm object representing user's inputs.
     * @return A newly created Conference Object.
     * @throws UnauthorizedException when the user is not signed in.
     */
    @ApiMethod(name = "createConference", path = "conference", httpMethod = HttpMethod.POST)
    public Conference createConference(final User user, final ConferenceForm conferenceForm)
        throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        final String userId = user.getUserId();
        Key<Profile> profileKey = Key.create(Profile.class, userId);
        final Key<Conference> conferenceKey = factory().allocateId(profileKey, Conference.class);
        final long conferenceId = conferenceKey.getId();
        final Queue queue = QueueFactory.getDefaultQueue();
        
        // Start a transaction.
        Conference conference = ofy().transact(new Work<Conference>() {
        	@Override
        	public Conference run() {
                Profile profile = getProfileFromUser(user);
                Conference conference = new Conference(conferenceId, userId, conferenceForm);
                ofy().save().entities(profile, conference).now();
                
                queue.add(ofy().getTransaction(), 
                		TaskOptions.Builder.withUrl("/tasks/send_confirmation_email")
                		.param("email", profile.getMainEmail())
                		.param("conferenceInfo", conference.toString()));

                return conference;
        	}
        });
        return conference;
    }
    
    @ApiMethod(
            name = "queryConferences",
            path = "queryConferences",
            httpMethod = HttpMethod.POST
    )
    public List<Conference> queryConferences(ConferenceQueryForm conferenceQueryForm) {
        Iterable<Conference> conferenceIterable = conferenceQueryForm.getQuery();
        List<Conference> result = new ArrayList<>(0);
        List<Key<Profile>> organizersKeyList = new ArrayList<>(0);
        
        for (Conference conference : conferenceIterable) {
        	organizersKeyList.add(Key.create(Profile.class, conference.getOrganizerUserId()));
        	result.add(conference);
        }
        ofy().load().keys(organizersKeyList);
        
        return result;
    }
    
    @ApiMethod(
            name = "getConferencesCreated",
            path = "getConferencesCreated",
            httpMethod = HttpMethod.POST
    )
    public List<Conference> getConferencesCreated(final User user)
        throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        
        Key<Profile> userKey = Key.create(Profile.class, user.getUserId());
        Query<Conference> query = ofy().load().type(Conference.class).ancestor(userKey);
        
        return query.list();
    }
    
    /**
     * Returns a Conference object with the given conferenceId.
     *
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return a Conference object with the given conferenceId.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "getConference",
            path = "conference/{websafeConferenceKey}",
            httpMethod = HttpMethod.GET
    )
    public Conference getConference(
            @Named("websafeConferenceKey") final String websafeConferenceKey)
            throws NotFoundException {
        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        Conference conference = ofy().load().key(conferenceKey).now();
        if (conference == null) {
            throw new NotFoundException("No Conference found with key: " + websafeConferenceKey);
        }
        return conference;
    }


    /**
     * Just a wrapper for Boolean.
     * We need this wrapped Boolean because endpoints functions must return
     * an object instance, they can't return a Type class such as
     * String or Integer or Boolean
     */
    public static class WrappedBoolean {

        private final Boolean result;
        private final String reason;

        public WrappedBoolean(Boolean result) {
            this.result = result;
            this.reason = "";
        }

        public WrappedBoolean(Boolean result, String reason) {
            this.result = result;
            this.reason = reason;
        }

        public Boolean getResult() {
            return result;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Register to attend the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return Boolean true when success, otherwise false
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "registerForConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.POST
    )
    public WrappedBoolean registerForConference(final User user,
            @Named("websafeConferenceKey") final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException,
            ForbiddenException, ConflictException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        // Start transaction
        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
        	public WrappedBoolean run() {
                try {
	            Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
	            Conference conference = ofy().load().key(conferenceKey).now();

	            if (conference == null) {
	                return new WrappedBoolean (false,
	                        "No Conference found with key: "
	                                + websafeConferenceKey);
	            }
	
	            Profile profile = getProfileFromUser(user);
	
	            if (profile.getConferenceKeysToAttend().contains(
	                    websafeConferenceKey)) {
	                return new WrappedBoolean (false, "Already registered");
	            } else if (conference.getSeatsAvailable() <= 0) {
	                return new WrappedBoolean (false, "No seats available");
	            } else {
	                profile.addToConferenceKeysToAttend(websafeConferenceKey);
	                conference.bookSeats(1);
	                ofy().save().entities(profile, conference).now();
	                
	                return new WrappedBoolean(true, "Registration successful");
	            }
	
	            }
	            catch (Exception e) {
	                return new WrappedBoolean(false, "Unknown exception");
	            }
            }
        });
        if (!result.getResult()) {
            if (result.getReason().contains("No Conference found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else if (result.getReason() == "Already registered") {
                throw new ConflictException("You have already registered");
            }
            else if (result.getReason() == "No seats available") {
                throw new ConflictException("There are no seats available");
            }
            else {
                throw new ForbiddenException("Unknown exception");
            }
        }
        return result;
    }

    /**
     * Unregister from the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key to unregister
     *                             from.
     * @return Boolean true when success, otherwise false.
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "unregisterFromConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.DELETE
    )
    public WrappedBoolean unregisterFromConference(final User user,
                                            @Named("websafeConferenceKey")
                                            final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
            public WrappedBoolean run() {
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
                Conference conference = ofy().load().key(conferenceKey).now();
                if (conference == null) {
                    return new  WrappedBoolean(false,
                            "No Conference found with key: " + websafeConferenceKey);
                }

                Profile profile = getProfileFromUser(user);
                if (profile.getConferenceKeysToAttend().contains(websafeConferenceKey)) {
                    profile.unregisterFromConference(websafeConferenceKey);
                    conference.giveBackSeats(1);
                    ofy().save().entities(profile, conference).now();
                    return new WrappedBoolean(true);
                } else {
                    return new WrappedBoolean(false, "You are not registered for this conference");
                }
            }
        });
        if (!result.getResult()) {
            if (result.getReason().contains("No Conference found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else {
                throw new ForbiddenException(result.getReason());
            }
        }
        return new WrappedBoolean(result.getResult());
    }

    /**
     * Returns a collection of Conference Object that the user is going to attend.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @return a Collection of Conferences that the user is going to attend.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(
            name = "getConferencesToAttend",
            path = "getConferencesToAttend",
            httpMethod = HttpMethod.GET
    )
    public Collection<Conference> getConferencesToAttend(final User user)
            throws UnauthorizedException, NotFoundException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        
        Profile profile = getProfileFromUser(user); 
        if (profile == null) {
            throw new NotFoundException("Profile doesn't exist.");
        }

        List<String> keyStringsToAttend = profile.getConferenceKeysToAttend(); 
        List<Key<Conference>> keysToAttend = new ArrayList<>();
        for (String keyString : keyStringsToAttend) {
            keysToAttend.add(Key.<Conference>create(keyString));
        }
        
        return ofy().load().keys(keysToAttend).values();
    }
    
    @ApiMethod(
    		name = "getAnnouncement",
    		path = "announcement",
    		httpMethod = HttpMethod.GET
    )
    public Announcement getAnnouncement() {
    	MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
    	String announcementKey = Constants.MEMCACHE_ANNOUNCEMENTS_KEY;
    	Object message = memcacheService.get(announcementKey);
    	if (message != null) {
    		return new Announcement(message.toString());
    	}
    	return null;
    }

    /**
     * Creates a new Session object and stores it to the datastore.
     *
     * @param user A user who invokes this method, null when the user is not signed in.
     * @param sessionForm A SessionForm object representing user's inputs.
     * @return A newly created Session Object.
     * @throws UnauthorizedException when the user is not signed in.
     */
    @ApiMethod(name = "createSession", path = "createSession", httpMethod = HttpMethod.POST)
    public Session createSession(final User user, final SessionForm sessionForm, @Named("websafeConferenceKey") final String websafeConferenceKey)
        throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        final Key<Session> sessionKey = factory().allocateId(conferenceKey, Session.class);
        final long sessionId = sessionKey.getId();

        Session session = new Session(sessionId, websafeConferenceKey, sessionForm);
        ofy().save().entity(session).now();

        // Setting featured speaker and sessions.
        List<Session> sessionsBySpeaker = ofy().load().type(Session.class).ancestor(conferenceKey).filter("speaker =", session.getSpeaker()).list();
        List<String> sessionNames = new ArrayList<>(0);
        for (Session eachSession : sessionsBySpeaker) {
            sessionNames.add(eachSession.getName());
        }
        if (sessionNames.size() > 1) {
            StringBuilder featuredSpeakerStringBuilder = new StringBuilder(
                    session.getSpeaker() + ": ");
            Joiner joiner = Joiner.on(", ").skipNulls();
            featuredSpeakerStringBuilder.append(joiner.join(sessionNames));

            MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();

            String featuredSpeakerKey = Constants.MEMCACHE_FEATURED_SPEAKER_KEY;
            String featuredSpeakerText = featuredSpeakerStringBuilder.toString();

            memcacheService.put(featuredSpeakerKey, featuredSpeakerText);
        }
                
        return session;
    }

    /**
     * Returns a list of Session objects with the given conference.
     *
     * @param conference a conference which helds the sessions.
     * @return a Session object with the given conference.
     */
    @ApiMethod(
            name = "getConferenceSessions",
            path = "getConferenceSessions",
            httpMethod = HttpMethod.POST
    )
    public List<Session> getConferenceSessions(@Named("websafeConferenceKey") final String websafeConferenceKey) {        
        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        Query<Session> query = ofy().load().type(Session.class).ancestor(conferenceKey).order("name");
        
        return query.list();
    }

    /**
     * Returns a list of Session objects with the given conference and type of session.
     *
     * @param conference a conference which helds the sessions.
     * @param typeOfSession type of the session.
     * @return a Session object with the given conference and type of session.
     */
    @ApiMethod(
            name = "getConferenceSessionsByType",
            path = "getConferenceSessionsByType",
            httpMethod = HttpMethod.POST
    )
    public List<Session> getConferenceSessionsByType(@Named("websafeConferenceKey") final String websafeConferenceKey,
            @Named("typeOfSession") final String typeOfSession) {
        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        Query<Session> sessionsByType = ofy().load().type(Session.class).ancestor(conferenceKey).filter("typeOfSession =", typeOfSession).order("name");

        return sessionsByType.list();
    }

    /**
     * Returns a list of Session objects with the given speaker.
     *
     * @param speaker a name of the speaker for the session.
     * @return a Session object with the given speaker.
     */
    @ApiMethod(
            name = "getSessionsBySpeaker",
            path = "getSessionsBySpeaker",
            httpMethod = HttpMethod.POST
    )
    public List<Session> getSessionsBySpeaker(@Named("speaker") final String speaker) {
        Query<Session> sessionsBySpeaker = ofy().load().type(Session.class).filter("speaker =", speaker);

        return sessionsBySpeaker.list();
    }

    /**
     * Add the specified session to wishlist.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeSessionKey The String representation of the Session Key.
     * @return Boolean true when success, otherwise false
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Session with the given sessionId.
     */
    @ApiMethod(
            name = "addSessionToWishlist",
            path = "conference/session/{websafeSessionKey}/wishlist",
            httpMethod = HttpMethod.POST
    )
    public WrappedBoolean addSessionToWishlist(final User user,
            @Named("websafeSessionKey") final String websafeSessionKey)
            throws UnauthorizedException, NotFoundException,
            ForbiddenException, ConflictException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        // Start transaction
        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
            public WrappedBoolean run() {
                try {
                Key<Session> sessionKey = Key.create(websafeSessionKey);
                Session session = ofy().load().key(sessionKey).now();

                if (session == null) {
                    return new WrappedBoolean (false,
                            "No session found with key: "
                                    + websafeSessionKey);
                }
    
                Profile profile = getProfileFromUser(user);
    
                if (profile.getSessionKeysInWishlist().contains(
                        websafeSessionKey)) {
                    return new WrappedBoolean (false, "Already in the wishlist");
                } else {
                    profile.addToSessionKeysInWishlist(websafeSessionKey);
                    ofy().save().entity(profile).now();
                    
                    return new WrappedBoolean(true, "Successfully added to your wishlist");
                }
    
                }
                catch (Exception e) {
                    return new WrappedBoolean(false, "Unknown exception");
                }
            }
        });
        if (!result.getResult()) {
            if (result.getReason().contains("No Session found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else if (result.getReason() == "Already in the wishlist") {
                throw new ConflictException("You have already added to your wishlist");
            }
            else {
                throw new ForbiddenException("Unknown exception");
            }
        }
        return result;
    }

    /**
     * Remove the specified session from the user's wishlist.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeSessionKey The String representation of the Session Key to remove
     *                             from user's wishlist.
     * @return Boolean true when success, otherwise false.
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Session with the given sessionId.
     */
    @ApiMethod(
            name = "RemoveSessionFromWishlist",
            path = "conference/session/{websafeSessionKey}/wishlist",
            httpMethod = HttpMethod.DELETE
    )
    public WrappedBoolean RemoveSessionFromWishlist(final User user,
                                            @Named("websafeSessionKey")
                                            final String websafeSessionKey)
            throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
            public WrappedBoolean run() {
                Key<Session> sessionKey = Key.create(websafeSessionKey);
                Session session = ofy().load().key(sessionKey).now();
                if (session == null) {
                    return new  WrappedBoolean(false,
                            "No Session found with key: " + websafeSessionKey);
                }

                Profile profile = getProfileFromUser(user);
                if (profile.getSessionKeysInWishlist().contains(websafeSessionKey)) {
                    profile.removeFromSessionKeysInWishlist(websafeSessionKey);
                    ofy().save().entity(profile).now();
                    return new WrappedBoolean(true);
                } else {
                    return new WrappedBoolean(false, "You've not added this session in your wishlist");
                }
            }
        });
        if (!result.getResult()) {
            if (result.getReason().contains("No Session found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else {
                throw new ForbiddenException(result.getReason());
            }
        }
        return new WrappedBoolean(result.getResult());
    }

    /**
     * Returns a collection of Session Object that the user has added to wishlist.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @return a Collection of Sessions that the user has added to wishlist.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(
            name = "getSessionsInWishlist",
            path = "getSessionsInWishlist",
            httpMethod = HttpMethod.GET
    )
    public Collection<Session> getSessionsInWishlist(final User user)
            throws UnauthorizedException, NotFoundException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        
        Profile profile = getProfileFromUser(user); 
        if (profile == null) {
            throw new NotFoundException("Profile doesn't exist.");
        }

        List<String> keyStringsInWishlist = profile.getSessionKeysInWishlist(); 
        List<Key<Session>> keysInWishlist = new ArrayList<>();
        for (String keyString : keyStringsInWishlist) {
            keysInWishlist.add(Key.<Session>create(keyString));
        }
        
        return ofy().load().keys(keysInWishlist).values();
    }

    /**
     * Returns Announcement for featured speaker and sessions.
     * @return Announcement for featured speaker and sessions.
     */
    @ApiMethod(
            name = "getFeaturedSpeaker",
            path = "getFeaturedSpeaker",
            httpMethod = HttpMethod.GET
    )
    public Announcement getFeaturedSpeaker() {
        MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
        String featuredSpeakerKey = Constants.MEMCACHE_FEATURED_SPEAKER_KEY;
        Object message = memcacheService.get(featuredSpeakerKey);
        if (message != null) {
            return new Announcement(message.toString());
        }
        return null;
    }

    /**
     * Returns sessions queried by user.
     *
     * @param sessionQueryForm A SessionQueryForm object representing user's inputs.
     * @return sessions queried by user.
     */
    @ApiMethod(
            name = "querySessions",
            path = "querySessions",
            httpMethod = HttpMethod.POST
    )
    public List<Session> querySessions(SessionQueryForm sessionQueryForm) {
        Iterable<Session> sessionIterable = sessionQueryForm.getQuery();
        List<Session> result = new ArrayList<>(0);

        for (Session session : sessionIterable) {
            result.add(session);
        }
        
        return result;
    }    

    /**
     * A method for query problem in final project rublic.
     * This method is designed for retrun sessions that starts before 7pm and has non-workshop type of session.
     * 
     * @param startTime start time of the session.
     * @param typeOfSession type of the session.
     * @return list of sessions which starts before the startTime parameter, and excludes typeOfSession parameter.
     */
    @ApiMethod(
            name = "queryProblem",
            path = "queryProblem",
            httpMethod = HttpMethod.GET
    )
    public List<Session> queryProblem(@Named("startTime") final int startTime, 
        @Named("typeOfSession") final String typeOfSession) {
        List<Session> sessionsByStartTime = ofy().load().type(Session.class).filter("startTime <", startTime).list();
        List<Session> sessionsByTypeOfSession = ofy().load().type(Session.class).filter("typeOfSession =", typeOfSession).list();
        for (Session session : sessionsByTypeOfSession) {
            if (sessionsByStartTime.contains(session)) {
                sessionsByStartTime.remove(session);
            }
        }
        return sessionsByStartTime;
    }
}
