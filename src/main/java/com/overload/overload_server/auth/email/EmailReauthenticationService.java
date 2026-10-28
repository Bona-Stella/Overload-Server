package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailReauthenticationService {
	private final OtpChallenges challenges;
	private final AccountIdentityService identities;
	private final SessionReauthentication sessions;
	private final ReauthenticationEmails contacts;

	public EmailReauthenticationService(EmailOtpService login, AccountIdentityService identities, SessionReauthentication sessions, ReauthenticationEmails contacts) {
		this.challenges = login.challenges(); this.identities = identities; this.sessions = sessions; this.contacts=contacts;
	}

	public UUID request(UUID user, UUID identity, HttpServletRequest request) {
		var snapshot = sessions.capture(request, user);
		var context = context(snapshot, identity);
		return challenges.request(context, request.getRemoteAddr(), () -> validate(context, snapshot, identity));
	}

	public void verify(UUID user, UUID identity, UUID challenge, String code, HttpServletRequest request, HttpServletResponse response) {
		var snapshot = sessions.capture(request, user);
		var context = context(snapshot, identity);
		var rotation = challenges.consume(context, challenge, code, request.getRemoteAddr(),
				() -> validate(context, snapshot, identity), now -> {
					var updated = sessions.rotate(snapshot);
					sessions.validateRotation(updated);
					return updated;
				});
		sessions.complete(request, response, rotation);
	}

	private OtpChallenges.Context context(SessionReauthentication.Snapshot snapshot, UUID identity) {
		var legacy=identity==null?null:identities.email(snapshot.userId(),identity,false).orElseThrow(()->new OtpFailure(404,"IDENTITY_NOT_FOUND"));
		var contact=contacts.contact(snapshot.userId()).orElseThrow(()->new OtpFailure(409,"REAUTH_EMAIL_REQUIRED"));
		if(legacy!=null&&!legacy.email().equals(contact.email())){throw new OtpFailure(409,"REAUTH_EMAIL_MISMATCH");}
		return new OtpChallenges.Context(OtpPurpose.REAUTH,contact.email(),ReauthenticationEmails.binding(snapshot,contact.id()),snapshot.userId(),null,contact.id());
	}

	private void validate(OtpChallenges.Context context, SessionReauthentication.Snapshot snapshot, UUID legacyIdentity) {
		contacts.lockUser(snapshot.userId());
		var contact=contacts.contact(snapshot.userId()).orElseThrow(()->new OtpFailure(409,"REAUTH_EMAIL_REQUIRED"));
		if(!contact.id().equals(context.contactId())||!contact.email().equals(context.email())){throw new SessionReauthentication.SessionChanged();}
		if(legacyIdentity!=null){var owned=identities.email(snapshot.userId(),legacyIdentity,true).orElseThrow(()->new OtpFailure(404,"IDENTITY_NOT_FOUND"));
			if(!owned.email().equals(contact.email())){throw new OtpFailure(409,"REAUTH_EMAIL_MISMATCH");}}
		sessions.validate(snapshot);
	}

	public java.util.Map<String,Object> status(UUID user,HttpServletRequest request){return contacts.status(sessions.capture(request,user));}
	public UUID enrollRequest(UUID user,UUID candidateId,HttpServletRequest request) {
		var snapshot=sessions.capture(request,user);var candidate=contacts.candidate(snapshot,candidateId);
		var context=new OtpChallenges.Context(OtpPurpose.CONTACT_ENROLL,candidate.email(),candidate.binding(),user,candidate.identity());
		return challenges.request(context,request.getRemoteAddr(),()->validateCandidate(snapshot,candidate),now->contacts.reservedCandidate(snapshot,candidate.id(),now));
	}
	public void enrollVerify(UUID user,UUID candidateId,UUID challenge,String code,HttpServletRequest request,HttpServletResponse response) {
		var snapshot=sessions.capture(request,user);var candidate=contacts.candidate(snapshot,candidateId);
		var context=new OtpChallenges.Context(OtpPurpose.CONTACT_ENROLL,candidate.email(),candidate.binding(),user,candidate.identity());
		var rotation=challenges.consume(context,challenge,code,request.getRemoteAddr(),()->validateCandidate(snapshot,candidate),now->{
			contacts.registerCandidate(snapshot,candidate,now);
			var updated=sessions.rotate(snapshot);sessions.validateRotation(updated);return updated;
		});
		sessions.complete(request,response,rotation);
	}
	private void validateCandidate(SessionReauthentication.Snapshot snapshot,ReauthenticationEmails.Candidate candidate) {
		contacts.lockUser(snapshot.userId());
		if(contacts.contact(snapshot.userId()).isPresent()){throw new OtpFailure(409,"REAUTH_EMAIL_ALREADY_VERIFIED");}
		contacts.candidate(snapshot,candidate.id());
		var identity=identities.google(snapshot.userId(),candidate.identity(),true).orElseThrow(()->new OtpFailure(404,"IDENTITY_NOT_FOUND"));
		if(!identity.subject().equals(candidate.subject())){throw new SessionReauthentication.SessionChanged();}
		// The candidate was created only after official signed email/issuer/sub verification in the bound OIDC flow.
		sessions.validate(snapshot);
	}
	public void cancel(UUID user){contacts.cancel(user);}
}
