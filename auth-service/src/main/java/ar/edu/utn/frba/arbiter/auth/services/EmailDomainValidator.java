package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEmailDomainException;
import org.springframework.stereotype.Component;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * Checks the email's domain has MX records (DNS) before creating it — filters out made-up domains
 * (asdf.qwerty) without sending a real mail. It doesn't verify the specific mailbox exists, only
 * that the domain can receive mail.
 */
@Component
public class EmailDomainValidator {

    public void validate(String email) {
        String domain = email.substring(email.indexOf('@') + 1);

        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");

        try {
            Attributes attributes = new InitialDirContext(env).getAttributes(domain, new String[]{"MX"});
            Attribute mxRecords = attributes.get("MX");
            if (mxRecords == null || mxRecords.size() == 0) {
                throw new InvalidEmailDomainException(email);
            }
        } catch (NamingException e) {
            throw new InvalidEmailDomainException(email);
        }
    }
}
