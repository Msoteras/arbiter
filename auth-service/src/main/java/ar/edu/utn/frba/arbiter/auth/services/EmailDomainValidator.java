package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEmailDomainException;
import org.springframework.stereotype.Component;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/** Rejects domains without MX records. Doesn't verify that the mailbox itself exists. */
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
