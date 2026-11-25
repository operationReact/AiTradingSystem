export const ORDER_EXPORT_COLUMNS = [
    { label: 'Created at', value: (r) => r.createdAt }, { label: 'Symbol', value: (r) => r.symbol },
    { label: 'Side', value: (r) => r.side }, { label: 'Product', value: (r) => r.productType },
    { label: 'Submitted quantity', value: (r) => r.submittedQuantity ?? r.quantity }, { label: 'Filled quantity', value: (r) => r.filledQuantity ?? 0 },
    { label: 'Entry', value: (r) => r.entryPrice }, { label: 'Status', value: (r) => r.status }, { label: 'Order ID', value: (r) => r.id },
  ];
