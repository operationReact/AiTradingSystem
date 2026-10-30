/** Read-only request projection for presentation. Request execution remains a controller callback. */
export function requestState(result) {
  return {
    data: result.data, error: result.error, isError: result.isError, isPending: result.isPending,
    isLoading: result.isLoading, isFetching: result.isFetching,
  };
}
