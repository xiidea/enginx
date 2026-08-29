package net.xiidea.enginx.domain.certificate;

public enum CertificateProviderKind {
    /** Issued automatically over ACME. */
    ACME,
    /** Supplied by an operator. The platform stores and deploys it but cannot renew it. */
    MANUAL
}
