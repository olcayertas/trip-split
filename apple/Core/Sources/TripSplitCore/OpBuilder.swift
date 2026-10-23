// Builds and checks op envelopes for the writing side (SPEC §4.1, §4.2).

/// Builds an op envelope with its hash. The caller supplies author, seq and the HLC stamp.
public func buildOp(group: String, author: String, seq: Int64, hlc: String, type: String, body: JSONValue) -> JSONValue {
    let unsigned: [String: JSONValue] = [
        "v": .int(1), "group": .string(group), "author": .string(author), "seq": .int(seq),
        "hlc": .string(hlc), "type": .string(type), "body": body,
    ]
    var o = unsigned
    o["hash"] = .string(opHash(.object(unsigned)))
    return .object(o)
}

/// True when the type is known and the body passes the validation of SPEC §4.3.
public func isValidBody(type: String, body: JSONValue) -> Bool {
    knownTypes.contains(type) && (try? checkBody(type: type, body: body)) != nil
}

public func isValidId(_ s: String) -> Bool { isUUID(s) }
