package ar.edu.utn.frba.arbiter.cases.exceptions;

public class DocumentCorrectionNotAllowedException extends RuntimeException {

    private DocumentCorrectionNotAllowedException(String message) {
        super(message);
    }

    public static DocumentCorrectionNotAllowedException caseClosed() {
        return new DocumentCorrectionNotAllowedException(
                "No se pueden corregir los documentos de un expediente cerrado.");
    }

    public static DocumentCorrectionNotAllowedException notRead() {
        return new DocumentCorrectionNotAllowedException("Arbiter no leyó este documento.");
    }

    public static DocumentCorrectionNotAllowedException missingFieldName() {
        return new DocumentCorrectionNotAllowedException("Elegí el dato que querés corregir.");
    }

    public static DocumentCorrectionNotAllowedException unknownField(String fieldName) {
        return new DocumentCorrectionNotAllowedException(
                "Arbiter no leyó «" + fieldName + "» en este documento.");
    }

    public static DocumentCorrectionNotAllowedException invalidDate() {
        return new DocumentCorrectionNotAllowedException("La fecha no es válida.");
    }

    public static DocumentCorrectionNotAllowedException invalidAmount() {
        return new DocumentCorrectionNotAllowedException(
                "El importe tiene que ser mayor a cero, con hasta dos decimales.");
    }

    public static DocumentCorrectionNotAllowedException tooLong(int maxLength) {
        return new DocumentCorrectionNotAllowedException(
                "El dato tiene que tener hasta " + maxLength + " caracteres.");
    }

    public static DocumentCorrectionNotAllowedException sameValue() {
        return new DocumentCorrectionNotAllowedException("El documento ya tiene ese dato.");
    }
}
