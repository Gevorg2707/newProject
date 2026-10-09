package am.retailai.advice;

/** The human decision. Accepting a recommendation records intent only; no order, ad or payment is created. */
public enum Decision { ACCEPTED, REJECTED, NEED_DATA }
